package com.orinuno.controller;

import com.orinuno.jutsu.JutsuClient;
import com.orinuno.jutsu.filter.JutsuCatalogFilter;
import com.orinuno.jutsu.filter.JutsuGenre;
import com.orinuno.jutsu.filter.JutsuSort;
import com.orinuno.jutsu.filter.JutsuType;
import com.orinuno.jutsu.filter.JutsuYear;
import com.orinuno.model.dto.jutsu.JutsuAnimeInfoDto;
import com.orinuno.model.dto.jutsu.JutsuCatalogPageDto;
import com.orinuno.model.dto.jutsu.JutsuDriftSnapshotDto;
import com.orinuno.model.dto.jutsu.JutsuEpisodeMetaDto;
import com.orinuno.model.dto.jutsu.JutsuNoticeFeedDto;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Nullable;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * REST surface for the new jut.su SDK operations introduced alongside the schema-drift effort.
 *
 * <p>All endpoints live under {@code /api/v1/sources/jutsu/...} to match the existing source-aware
 * branch (the streaming proxy is at {@code /api/v1/sources/jutsu/stream}). Auth is intentionally
 * NOT routed through {@code ApiKeyAuthFilter} — this surface is the same one the demo UI uses, so
 * the API-key gate would prevent embedded-browser consumption. Premium / privileged operations live
 * under the parse / download trees and remain gated.
 *
 * <p>Endpoints:
 *
 * <ul>
 *   <li>{@code GET .../catalog} — browse / filter / sort the catalog
 *   <li>{@code GET .../search} — title search (composes with filters)
 *   <li>{@code GET .../anime/{slug}} — full anime info page
 *   <li>{@code GET .../episode?url=...} — single-episode metadata (no decode)
 *   <li>{@code GET .../notice} — upcoming-releases feed; one page or streamed walk
 *   <li>{@code GET .../drift} — current SDK drift snapshot for dashboards / health checks
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/sources/jutsu")
@Tag(name = "JutSu", description = "jut.su catalog / info / notice / drift surface")
public class JutsuApiController {

    private final JutsuClient jutsuClient;

    public JutsuApiController(JutsuClient jutsuClient) {
        this.jutsuClient = jutsuClient;
    }

    // -------------------------------------------------------------------------
    // Catalog browse / filter
    // -------------------------------------------------------------------------

    @GetMapping("/catalog")
    @Operation(
            summary = "Browse the jut.su catalog with optional filters and sort",
            description =
                    "Maps to POST /anime/{slug}/ on jut.su with the given filter slug. Filter"
                        + " parameters accept either the URL slug (e.g. `action`, `before2000`,"
                        + " `order-by-name`) or the SDK enum name (e.g. `ACTION`, `BEFORE_2000`,"
                        + " `BY_NAME`). Both forms are equivalent — the response always echoes"
                        + " slugs, so a value taken straight from the response can be sent back as"
                        + " a query parameter without translation.")
    public Mono<ResponseEntity<JutsuCatalogPageDto>> browseCatalog(
            @Parameter(description = "1-based page index", example = "1")
                    @RequestParam(defaultValue = "1")
                    int page,
            @Parameter(
                            description =
                                    "Genre filter (repeatable). Slugs from response: adventure,"
                                            + " action, comedy, everyday, romance, drama,"
                                            + " fantastic, fantasy, mystic, detective, thriller,"
                                            + " psychology.",
                            array =
                                    @ArraySchema(
                                            schema = @Schema(implementation = JutsuGenre.class)))
                    @RequestParam(required = false)
                    @Nullable
                    List<String> genres,
            @Parameter(
                            description =
                                    "Type filter (repeatable). Slugs: fighting, vampire, military,"
                                            + " demons, game, historical, space, magic, mecha,"
                                            + " music, parody, police, samurai, shojo, shonen,"
                                            + " sport, superpower, horror, school.",
                            array =
                                    @ArraySchema(
                                            schema = @Schema(implementation = JutsuType.class)))
                    @RequestParam(required = false)
                    @Nullable
                    List<String> types,
            @Parameter(
                            description =
                                    "Year filter (repeatable). Slugs: ongoing, 2026, 2025, 2024,"
                                            + " 2015-2023, 2008-2014, 2000-2007, before2000.",
                            array =
                                    @ArraySchema(
                                            schema = @Schema(implementation = JutsuYear.class)))
                    @RequestParam(required = false)
                    @Nullable
                    List<String> years,
            @Parameter(
                            description =
                                    "Sort order. Slugs: order-by-name, order-by-count,"
                                            + " order-by-date, order-by-add. BY_RATING is the"
                                            + " website's default and is elided from the URL slug —"
                                            + " sending it explicitly is a no-op.",
                            schema = @Schema(implementation = JutsuSort.class))
                    @RequestParam(required = false)
                    @Nullable
                    String sort) {
        JutsuCatalogFilter filter = buildFilter(genres, types, years, sort);
        return jutsuClient
                .browseCatalog(filter, page)
                .map(JutsuCatalogPageDto::from)
                .map(ResponseEntity::ok);
    }

    // -------------------------------------------------------------------------
    // Title search (composes with filters)
    // -------------------------------------------------------------------------

    @GetMapping("/search")
    @Operation(
            summary = "Search the jut.su catalog by title (composes with filters)",
            description =
                    "Sends the same POST as /catalog with the show_search form field populated."
                            + " Filter parameters mean exactly what they mean for /catalog and"
                            + " accept slug or enum-name input identically.")
    public Mono<ResponseEntity<JutsuCatalogPageDto>> searchCatalog(
            @Parameter(
                            description = "Title fragment (Russian or original)",
                            example = "наруто",
                            required = true)
                    @RequestParam("q")
                    String query,
            @Parameter(description = "1-based page index", example = "1")
                    @RequestParam(defaultValue = "1")
                    int page,
            @Parameter(
                            description =
                                    "Genre filter (repeatable). See /catalog for the slug list.",
                            array =
                                    @ArraySchema(
                                            schema = @Schema(implementation = JutsuGenre.class)))
                    @RequestParam(required = false)
                    @Nullable
                    List<String> genres,
            @Parameter(
                            description =
                                    "Type filter (repeatable). See /catalog for the slug list.",
                            array =
                                    @ArraySchema(
                                            schema = @Schema(implementation = JutsuType.class)))
                    @RequestParam(required = false)
                    @Nullable
                    List<String> types,
            @Parameter(
                            description =
                                    "Year filter (repeatable). See /catalog for the slug list.",
                            array =
                                    @ArraySchema(
                                            schema = @Schema(implementation = JutsuYear.class)))
                    @RequestParam(required = false)
                    @Nullable
                    List<String> years,
            @Parameter(
                            description = "Sort order. See /catalog for the slug list.",
                            schema = @Schema(implementation = JutsuSort.class))
                    @RequestParam(required = false)
                    @Nullable
                    String sort) {
        JutsuCatalogFilter filter = buildFilter(genres, types, years, sort);
        Mono<com.orinuno.jutsu.catalog.JutsuCatalogPage> page$ =
                filter.isEmpty()
                        ? jutsuClient.searchByTitle(query, page)
                        : jutsuClient.searchByTitle(filter, query, page);
        return page$.map(JutsuCatalogPageDto::from).map(ResponseEntity::ok);
    }

    // -------------------------------------------------------------------------
    // Anime info page
    // -------------------------------------------------------------------------

    @GetMapping("/anime/{slug}")
    @Operation(
            summary = "Fetch the anime info page (GET /{slug}/) into a typed payload",
            description =
                    "Returns the full season/episode listing, plus chrome metadata (title,"
                            + " synopsis, year, genres, types, thumbnail). The episode list is"
                            + " grouped by season; single-season anime collapse into one block.")
    public Mono<ResponseEntity<JutsuAnimeInfoDto>> getAnimeInfo(
            @Parameter(
                            description = "Anime slug (the path segment used by jut.su URLs)",
                            example = "naruto",
                            required = true)
                    @PathVariable
                    String slug) {
        return jutsuClient.getAnimeInfo(slug).map(JutsuAnimeInfoDto::from).map(ResponseEntity::ok);
    }

    // -------------------------------------------------------------------------
    // Single-episode metadata (no decode)
    // -------------------------------------------------------------------------

    @GetMapping("/episode")
    @Operation(
            summary = "Fetch one episode's chrome metadata without invoking the decoder",
            description =
                    "Use when you only need title / thumbnail / prev-next / paywall flag for a"
                            + " catalogue UI. Use POST /api/v1/sources/jutsu/decode (on"
                            + " SourcesController) when you also need the actual mp4 URLs.")
    public Mono<ResponseEntity<JutsuEpisodeMetaDto>> getEpisodeMeta(
            @Parameter(
                            description = "Full episode URL on jut.su",
                            example = "https://jut.su/onepuunchman/season-1/episode-1.html",
                            required = true)
                    @RequestParam
                    String url) {
        return jutsuClient
                .getEpisodeMeta(url)
                .map(JutsuEpisodeMetaDto::from)
                .map(ResponseEntity::ok);
    }

    // -------------------------------------------------------------------------
    // Notice feed
    // -------------------------------------------------------------------------

    @GetMapping("/notice")
    @Operation(
            summary = "Fetch one page of the upcoming-releases notice feed",
            description =
                    "When cursor is omitted, the SDK auto-discovers the freshest notice id by"
                            + " scraping the homepage. Returns up to 50 entries newest-first.")
    public Mono<ResponseEntity<JutsuNoticeFeedDto>> getNoticeFeed(
            @Parameter(
                            description =
                                    "notice_id cursor. Omit to get the freshest page (the SDK"
                                            + " auto-discovers the latest cursor by scraping the"
                                            + " homepage); pass a previous response's nextCursor to"
                                            + " walk backwards.",
                            example = "18729")
                    @RequestParam(required = false)
                    @Nullable
                    Integer cursor) {
        Mono<com.orinuno.jutsu.notice.JutsuNoticeFeed> source =
                cursor == null
                        ? jutsuClient.getLatestNoticeFeed()
                        : jutsuClient.getNoticeFeed(cursor);
        return source.map(JutsuNoticeFeedDto::from).map(ResponseEntity::ok);
    }

    @GetMapping(value = "/notice/stream", produces = "application/x-ndjson")
    @Operation(
            summary = "Walk notice feeds backwards as a streaming NDJSON Flux",
            description =
                    "Each element is one notice entry; the stream terminates at the history bound"
                            + " or the maxFeeds cap, whichever is reached first. Use with caution"
                            + " — each feed page costs one outbound request (1 RPS budget).")
    public Flux<JutsuNoticeFeedDto.JutsuNoticeEntryDto> streamNoticeEntries(
            @Parameter(
                            description = "Starting notice_id cursor (use latest from /notice)",
                            example = "18729",
                            required = true)
                    @RequestParam
                    int startCursor,
            @Parameter(
                            description =
                                    "Hard cap on feed pages walked. Each page costs one outbound"
                                        + " request against the 1 RPS budget — keep this small.",
                            example = "5")
                    @RequestParam(defaultValue = "5")
                    int maxFeeds) {
        return jutsuClient
                .streamNoticeEntries(startCursor, maxFeeds)
                .map(JutsuNoticeFeedDto.JutsuNoticeEntryDto::from);
    }

    // -------------------------------------------------------------------------
    // Drift snapshot
    // -------------------------------------------------------------------------

    @GetMapping(value = "/drift", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            summary = "Read the current jut.su SDK drift snapshot",
            description =
                    "HEALTHY when the recent window is empty; DEGRADED when one or more drift"
                            + " events have been observed (the parsers fired SELECTOR_MISS or"
                            + " similar). MultiSourceRanker auto-demotes jut.su when this is"
                            + " DEGRADED.")
    public ResponseEntity<JutsuDriftSnapshotDto> getDrift() {
        return ResponseEntity.ok(JutsuDriftSnapshotDto.from(jutsuClient.getDriftSnapshot()));
    }

    // -------------------------------------------------------------------------
    // Filter binding helpers
    // -------------------------------------------------------------------------

    private static JutsuCatalogFilter buildFilter(
            @Nullable List<String> genres,
            @Nullable List<String> types,
            @Nullable List<String> years,
            @Nullable String sort) {
        JutsuCatalogFilter.Builder b = JutsuCatalogFilter.builder();
        if (genres != null) {
            for (String g : genres)
                addByName(JutsuGenre.class, JutsuGenre::fromSlug, g, b::addGenre);
        }
        if (types != null) {
            for (String t : types) addByName(JutsuType.class, JutsuType::fromSlug, t, b::addType);
        }
        if (years != null) {
            for (String y : years) addByName(JutsuYear.class, JutsuYear::fromSlug, y, b::addYear);
        }
        if (sort != null && !sort.isBlank()) {
            JutsuSort parsed = parseSort(sort);
            if (parsed != null) b.sort(parsed);
        }
        return b.build();
    }

    /**
     * Resolve a filter parameter to its enum value, accepting either the SDK enum name (e.g. {@code
     * ACTION}) or the URL slug (e.g. {@code action}, {@code before2000}, {@code order-by-name}).
     * Both shapes are documented in the OpenAPI schema so callers can copy values from the response
     * directly into a follow-up request without translation.
     */
    private static <E extends Enum<E>> void addByName(
            Class<E> klass,
            java.util.function.Function<String, java.util.Optional<E>> bySlug,
            String raw,
            java.util.function.Consumer<E> sink) {
        if (raw == null || raw.isBlank()) return;
        String trimmed = raw.trim();
        var slugMatch = bySlug.apply(trimmed);
        if (slugMatch.isPresent()) {
            sink.accept(slugMatch.get());
            return;
        }
        try {
            sink.accept(Enum.valueOf(klass, trimmed.toUpperCase(java.util.Locale.ROOT)));
        } catch (IllegalArgumentException ex) {
            log.warn("Ignoring unknown {} value {}", klass.getSimpleName(), raw);
        }
    }

    @Nullable
    private static JutsuSort parseSort(String raw) {
        var slugMatch = JutsuSort.fromSlug(raw.trim());
        if (slugMatch.isPresent()) return slugMatch.get();
        try {
            return JutsuSort.valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            log.warn("Ignoring unknown sort {}", raw);
            return null;
        }
    }
}
