package com.orinuno.jutsu.read;

import com.orinuno.jutsu.filter.JutsuGenre;
import com.orinuno.jutsu.filter.JutsuSort;
import com.orinuno.jutsu.filter.JutsuType;
import com.orinuno.jutsu.filter.JutsuYear;
import com.orinuno.jutsu.model.JutsuEpisode;
import com.orinuno.jutsu.model.JutsuFilm;
import com.orinuno.jutsu.model.JutsuTitle;
import com.orinuno.jutsu.repository.JutsuEpisodeRepository;
import com.orinuno.jutsu.repository.JutsuFilmRepository;
import com.orinuno.jutsu.repository.JutsuTitleRepository;
import com.orinuno.model.dto.jutsu.JutsuAnimeInfoDto;
import com.orinuno.model.dto.jutsu.JutsuCatalogPageDto;
import jakarta.annotation.Nullable;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Read-only L1 cache service for jut.su catalog and anime-info endpoints (ARCH-0016 P1a Step 3.A).
 * All methods return {@link Optional#empty()} on cache-miss; callers are expected to chain a
 * fallback to the live SDK (see Step 3.B). The service never makes outbound calls and never writes
 * to the database — it's a pure read projection over what the sync workers populate.
 *
 * <p>Cache-miss semantics:
 *
 * <ul>
 *   <li>{@link #findCatalogPage(JutsuCatalogQuery)} — empty when zero rows match, OR when the
 *       caller asked for the unfiltered first page and the cache is empty enough that we can't
 *       trust the response (heuristic: if total count is zero, the cache hasn't been warmed yet and
 *       the fallback should hit live).
 *   <li>{@link #findAnimeInfo(String)} — empty when no row exists for the slug, OR when the row
 *       exists but {@code info_fetched_at} is {@code null} (catalog tick saw the slug but the
 *       info-page hydration hasn't happened yet — episodes are missing). Placeholder rows from the
 *       notice walker also fall in this bucket.
 * </ul>
 *
 * <p>Sort whitelist: {@link JutsuSort} is the only accepted source of truth for the {@code ORDER
 * BY} clause. Any other input — string slug, enum name, or anonymous garbage from the user — is
 * mapped through {@link #orderByFor(JutsuSort)} which produces a fixed SQL fragment per enum value.
 * There is no path from user input to interpolated SQL outside this whitelist.
 */
@Slf4j
@Service
public class JutsuCatalogReadService {

    /** Page size jut.su returns for its own catalog AJAX. We mirror it for shape parity. */
    public static final int PAGE_SIZE = 30;

    private final JutsuTitleRepository titleRepository;
    private final JutsuEpisodeRepository episodeRepository;
    private final JutsuFilmRepository filmRepository;

    public JutsuCatalogReadService(
            JutsuTitleRepository titleRepository,
            JutsuEpisodeRepository episodeRepository,
            JutsuFilmRepository filmRepository) {
        this.titleRepository = titleRepository;
        this.episodeRepository = episodeRepository;
        this.filmRepository = filmRepository;
    }

    /**
     * Look up one catalog page from the L1 cache. Returns {@link Optional#empty()} on cache-miss so
     * the caller can fall back to a live request.
     */
    public Optional<JutsuCatalogPageDto> findCatalogPage(JutsuCatalogQuery query) {
        if (query == null) throw new IllegalArgumentException("query must not be null");
        int page = Math.max(1, query.page());
        List<String> genres = slugList(query.genres(), JutsuGenre::slug);
        List<String> types = slugList(query.types(), JutsuType::slug);
        List<String> years = slugList(query.years(), JutsuYear::slug);
        String orderBy = orderByFor(query.sort() == null ? JutsuSort.BY_RATING : query.sort());

        long total = titleRepository.countCatalogRows(genres, types, years);
        if (total == 0) {
            log.debug(
                    "jutsu-cache: catalog miss (no rows match filters) — page={}, genres={},"
                            + " types={}, years={}, sort={}",
                    page,
                    genres,
                    types,
                    years,
                    query.sort());
            return Optional.empty();
        }
        int offset = (page - 1) * PAGE_SIZE;
        List<JutsuTitle> rows =
                titleRepository.findCatalogPage(genres, types, years, orderBy, PAGE_SIZE, offset);
        return Optional.of(JutsuCatalogPageDto.fromCache(page, PAGE_SIZE, total, rows));
    }

    /**
     * Look up one anime info page from the L1 cache. Returns {@link Optional#empty()} when the slug
     * is unknown OR when the row is a notice-walker placeholder (no {@code info_fetched_at} stamp
     * yet); the caller falls back to live in those cases.
     */
    public Optional<JutsuAnimeInfoDto> findAnimeInfo(String slug) {
        if (slug == null || slug.isBlank()) {
            return Optional.empty();
        }
        Optional<JutsuTitle> row = titleRepository.findBySlug(slug);
        if (row.isEmpty()) {
            log.debug("jutsu-cache: info miss (slug not in L1) — slug={}", slug);
            return Optional.empty();
        }
        JutsuTitle title = row.get();
        if (title.getInfoFetchedAt() == null) {
            log.debug("jutsu-cache: info miss (placeholder, info_fetched_at null) — slug={}", slug);
            return Optional.empty();
        }
        List<JutsuEpisode> episodes = episodeRepository.findBySlug(slug);
        List<JutsuFilm> films = filmRepository.findBySlug(slug);
        return Optional.of(JutsuAnimeInfoDto.fromCache(title, episodes, films));
    }

    /**
     * Map {@link JutsuSort} to a fixed SQL fragment. The fragment is plugged into {@code ORDER BY
     * ${sort}} via MyBatis string interpolation (NOT a parameterised value), so the input MUST be a
     * whitelist match. No user-supplied string ever reaches this method directly.
     */
    static String orderByFor(JutsuSort sort) {
        // Each fragment ends with `slug ASC` to break ties deterministically — paginated requests
        // with identical primary-sort values would otherwise return overlapping rows on retries.
        return switch (sort) {
                // BY_RATING — jut.su's "by rating" default is the page order itself, which the sync
                // worker stores as catalog_position. NULL positions (notice-walker placeholders)
                // sort
                // last so a partially-warmed cache still produces a stable tail.
            case BY_RATING -> "(catalog_position IS NULL) ASC, catalog_position ASC, slug ASC";
            case BY_NAME -> "title COLLATE utf8mb4_unicode_ci ASC, slug ASC";
            case BY_EPISODE_COUNT ->
                    "(catalog_episode_count IS NULL) ASC, catalog_episode_count DESC, slug ASC";
                // Year buckets are stored as text slugs ("2024", "2015-2023", "before2000",
                // "ongoing")
                // — lexicographic DESC on these is approximate but stable; truly ordering by
                // release
                // year would need a numeric column, tracked in BACKLOG.
            case BY_RELEASE_DATE -> "(year_bucket IS NULL) ASC, year_bucket DESC, slug ASC";
            case BY_DATE_ADDED -> "first_seen_at DESC, slug ASC";
        };
    }

    private static <E extends Enum<E>> List<String> slugList(
            @Nullable Set<E> values, java.util.function.Function<E, String> slug) {
        if (values == null || values.isEmpty()) return List.of();
        return values.stream().map(slug).distinct().sorted().toList();
    }

    /**
     * Filter inputs for {@link #findCatalogPage(JutsuCatalogQuery)}. {@code null} or empty sets
     * mean "no filter on this axis". Sort defaults to {@link JutsuSort#BY_RATING} when {@code
     * null}.
     */
    public record JutsuCatalogQuery(
            int page,
            @Nullable Set<JutsuGenre> genres,
            @Nullable Set<JutsuType> types,
            @Nullable Set<JutsuYear> years,
            @Nullable JutsuSort sort) {

        public static JutsuCatalogQuery unfiltered(int page) {
            return new JutsuCatalogQuery(page, null, null, null, null);
        }

        /**
         * Convenience: mirror what {@code JutsuApiController.buildFilter} produces from the raw
         * REST query. {@code filterSummary} is purely for logs / metrics — not used for SQL.
         */
        public Map<String, Object> filterSummary() {
            return Map.of(
                    "page", page,
                    "genres",
                            genres == null
                                    ? List.of()
                                    : genres.stream()
                                            .map(JutsuGenre::slug)
                                            .sorted()
                                            .collect(Collectors.toUnmodifiableList()),
                    "types",
                            types == null
                                    ? List.of()
                                    : types.stream()
                                            .map(JutsuType::slug)
                                            .sorted()
                                            .collect(Collectors.toUnmodifiableList()),
                    "years",
                            years == null
                                    ? List.of()
                                    : years.stream()
                                            .map(JutsuYear::slug)
                                            .sorted()
                                            .collect(Collectors.toUnmodifiableList()),
                    "sort", sort == null ? JutsuSort.BY_RATING.name() : sort.name());
        }
    }
}
