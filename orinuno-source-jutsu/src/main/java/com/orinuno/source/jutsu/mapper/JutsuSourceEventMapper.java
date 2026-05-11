/*
 * JutsuSourceEventMapper — ADR 0019 Phase 4.6.
 *
 * Renders L1 jut.su state (JutsuTitle + optional episodes/films lookups, fetched
 * by the projection) into producer-side SourceCatalogEvent payloads suitable
 * for the /api/v1/source-events/ready stream. Mirrors orinuno-source-kodik's
 * KodikSourceEventMapper.
 *
 * Mapping decisions:
 *   - Identifier: SourceIdentifier.of("jutsu", slug). slug is the jut.su URL
 *     stem and the durable id consumers stick to (Phase 5 meter watermarks
 *     poll-by-slug keyed updates).
 *   - kindHint: jut.su catalog rows have no kind discriminator in L1, so we
 *     default ANIME (the entire site is anime / anime-related); when an
 *     entry has only films and zero episodes the projection upgrades it to
 *     MovieDiscovered, otherwise SeriesDiscovered, with TitleObserved as the
 *     fallback when no playable URLs exist.
 *   - Provenance: sourceUrl="https://jut.su/anime/<slug>/", fetchedAt from
 *     catalog_fetched_at when present, otherwise info_fetched_at, otherwise
 *     last_seen_at — whichever's freshest in the L1 row.
 */
package com.orinuno.source.jutsu.mapper;

import com.orinuno.contract.source.ContentKindHint;
import com.orinuno.contract.source.ExternalIds;
import com.orinuno.contract.source.Provenance;
import com.orinuno.contract.source.SourceCatalogEvent;
import com.orinuno.contract.source.SourceContentInfo;
import com.orinuno.contract.source.SourceEpisode;
import com.orinuno.contract.source.SourceEpisodeVariant;
import com.orinuno.contract.source.SourceIdentifier;
import com.orinuno.contract.source.SourceSeason;
import com.orinuno.source.jutsu.model.JutsuEpisode;
import com.orinuno.source.jutsu.model.JutsuFilm;
import com.orinuno.source.jutsu.model.JutsuTitle;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class JutsuSourceEventMapper {

    private static final String SOURCE_TYPE = "jutsu";
    private static final String BASE_URL = "https://jut.su";

    private JutsuSourceEventMapper() {}

    public static SourceCatalogEvent toEvent(
            JutsuTitle title, List<JutsuEpisode> episodes, List<JutsuFilm> films, Clock clock) {
        SourceIdentifier identifier = SourceIdentifier.of(SOURCE_TYPE, title.getSlug());
        SourceContentInfo info = buildInfo(title);
        Provenance provenance = buildProvenance(title, clock);

        boolean hasEpisodes = episodes != null && !episodes.isEmpty();
        boolean hasFilms = films != null && !films.isEmpty();

        if (!hasEpisodes && !hasFilms) {
            return new SourceCatalogEvent.TitleObserved(identifier, info, provenance);
        }

        if (!hasEpisodes && hasFilms) {
            JutsuFilm first = films.get(0);
            SourceEpisodeVariant variant = filmToVariant(first);
            return new SourceCatalogEvent.MovieDiscovered(identifier, info, variant, provenance);
        }

        List<SourceSeason> seasons = groupEpisodesIntoSeasons(episodes);
        if (seasons.isEmpty()) {
            return new SourceCatalogEvent.TitleObserved(identifier, info, provenance);
        }
        return new SourceCatalogEvent.SeriesDiscovered(identifier, info, seasons, provenance);
    }

    private static SourceContentInfo buildInfo(JutsuTitle title) {
        return SourceContentInfo.builder()
                .titleRu(title.getTitle())
                .titleEn(title.getOriginalTitle())
                .year(parseYear(title.getYearBucket()))
                .kindHint(ContentKindHint.ANIME)
                .externalIds(ExternalIds.builder().build())
                .posterUrl(title.getThumbnailUrl())
                .build();
    }

    private static Integer parseYear(String yearBucket) {
        if (yearBucket == null || yearBucket.isBlank()) {
            return null;
        }
        // jut.su uses "2024" / "2023-2024" / "2020s" — pick the first 4-digit token.
        for (String token : yearBucket.split("[^0-9]+")) {
            if (token.length() == 4) {
                try {
                    return Integer.parseInt(token);
                } catch (NumberFormatException ignored) {
                    return null;
                }
            }
        }
        return null;
    }

    private static List<SourceSeason> groupEpisodesIntoSeasons(List<JutsuEpisode> episodes) {
        Map<Integer, List<JutsuEpisode>> bySeason = new LinkedHashMap<>();
        episodes.stream()
                .sorted(
                        Comparator.comparingInt(JutsuEpisode::getSeason)
                                .thenComparingInt(JutsuEpisode::getEpisode))
                .forEach(
                        ep ->
                                bySeason.computeIfAbsent(ep.getSeason(), k -> new ArrayList<>())
                                        .add(ep));

        return bySeason.entrySet().stream()
                .map(
                        entry -> {
                            List<SourceEpisode> sourceEpisodes =
                                    entry.getValue().stream()
                                            .map(JutsuSourceEventMapper::episodeRow)
                                            .toList();
                            return new SourceSeason(
                                    null, null, null, entry.getKey(), sourceEpisodes);
                        })
                .toList();
    }

    private static SourceEpisode episodeRow(JutsuEpisode ep) {
        SourceEpisodeVariant variant = episodeToVariant(ep);
        return new SourceEpisode(
                null, null, null, null, null, null, ep.getEpisode(), List.of(variant));
    }

    private static SourceEpisodeVariant episodeToVariant(JutsuEpisode ep) {
        return new SourceEpisodeVariant(
                SourceIdentifier.of(SOURCE_TYPE, episodeIdentifier(ep)),
                absoluteUrl(ep.getRelativeUrl()),
                ep.getLabel(),
                null,
                null,
                null);
    }

    private static SourceEpisodeVariant filmToVariant(JutsuFilm film) {
        return new SourceEpisodeVariant(
                SourceIdentifier.of(SOURCE_TYPE, filmIdentifier(film)),
                absoluteUrl(film.getRelativeUrl()),
                film.getLabel(),
                null,
                null,
                null);
    }

    private static String episodeIdentifier(JutsuEpisode ep) {
        return ep.getSlug() + "/s" + ep.getSeason() + "/e" + ep.getEpisode();
    }

    private static String filmIdentifier(JutsuFilm film) {
        return film.getSlug() + "/film/" + film.getFilmIndex();
    }

    private static String absoluteUrl(String relativeUrl) {
        if (relativeUrl == null || relativeUrl.isBlank()) {
            return null;
        }
        if (relativeUrl.startsWith("http://") || relativeUrl.startsWith("https://")) {
            return relativeUrl;
        }
        return BASE_URL + (relativeUrl.startsWith("/") ? relativeUrl : "/" + relativeUrl);
    }

    private static Provenance buildProvenance(JutsuTitle title, Clock clock) {
        Instant fetchedAt =
                Optional.ofNullable(
                                firstNonNull(
                                        title.getCatalogFetchedAt(),
                                        title.getInfoFetchedAt(),
                                        title.getLastSeenAt()))
                        .map(ldt -> ldt.toInstant(ZoneOffset.UTC))
                        .orElse(Instant.now(clock));
        String sourceUrl = BASE_URL + "/anime/" + title.getSlug() + "/";
        return Provenance.of(sourceUrl, fetchedAt);
    }

    private static LocalDateTime firstNonNull(LocalDateTime a, LocalDateTime b, LocalDateTime c) {
        if (a != null) return a;
        if (b != null) return b;
        return c;
    }
}
