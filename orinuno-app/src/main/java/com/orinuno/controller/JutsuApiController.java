package com.orinuno.controller;

import com.orinuno.jutsu.JutsuClient;
import com.orinuno.jutsu.catalog.JutsuCatalogRequest;
import com.orinuno.jutsu.fallback.JutsuLiveFallbackService;
import com.orinuno.jutsu.filter.JutsuCatalogFilter;
import com.orinuno.jutsu.filter.JutsuGenre;
import com.orinuno.jutsu.filter.JutsuSort;
import com.orinuno.jutsu.filter.JutsuType;
import com.orinuno.jutsu.filter.JutsuYear;
import com.orinuno.jutsu.read.JutsuCatalogReadService;
import com.orinuno.model.dto.jutsu.JutsuAnimeInfoDto;
import com.orinuno.model.dto.jutsu.JutsuCatalogPageDto;
import com.orinuno.model.dto.jutsu.JutsuDriftSnapshotDto;
import com.orinuno.model.dto.jutsu.JutsuNoticeFeedDto;
import com.orinuno.model.dto.jutsu.JutsuPageMetaDto;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Nullable;
import java.util.List;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * REST surface for the new jut.su SDK operations introduced alongside the schema-drift effort.
 *
 * <p>All endpoints live under {@code /api/v1/sources/jutsu/...} to match the existing source-aware
 * branch (the streaming proxy is at {@code /api/v1/sources/jutsu/stream}). Auth is intentionally
 * NOT routed through {@code ApiKeyAuthFilter} — this surface is the same one the demo UI uses, so
 * the API-key gate would prevent embedded-browser consumption. Premium / privileged operations live
 * under the parse / download trees and remain gated.
 *
 * <h3>Cache-first reads (ARCH-0016 P1a Step 3)</h3>
 *
 * <p>{@code /catalog} and {@code /anime/{slug}} are served from the L1 cache (populated by {@code
 * JutsuCatalogSyncService}) by default. On cache-miss the request is forwarded to {@link
 * JutsuLiveFallbackService} which is guarded by a dedicated rate-limit bucket, a rolling-window
 * circuit breaker, and a short-TTL negative cache. {@code /search} is intentionally NOT cached —
 * text search would multiply cache keys without benefit.
 *
 * <p>Each response carries a {@code Cache-Status} header (RFC 9211) so consumers can tell where the
 * payload came from:
 *
 * <ul>
 *   <li>{@code orinuno; hit} — served from L1 (the sync workers had a fresh copy)
 *   <li>{@code orinuno; fwd=miss; fallback} — L1 missed, live fallback succeeded
 *   <li>{@code orinuno; fwd=bypass} — endpoint bypasses the cache by design (search)
 * </ul>
 *
 * <p>When all three fallback guards reject (kill-switch off, breaker OPEN, negative-cache hit) we
 * return {@code 503 Service Unavailable} with an {@code X-Orinuno-Fallback-Reason} header
 * explaining which guard fired so dashboards / consumers can react. Same status code is used when
 * the live SDK call itself errors out (e.g. jut.su returned 5xx).
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/sources/jutsu")
@Tag(name = "JutSu", description = "jut.su catalog / info / notice / drift surface")
public class JutsuApiController {

    /** RFC 9211 header name. Spring doesn't expose a constant for this one. */
    private static final String CACHE_STATUS = "Cache-Status";

    /** Custom diagnostic header explaining why a 503 was returned (which guard or live error). */
    private static final String FALLBACK_REASON = "X-Orinuno-Fallback-Reason";

    private static final String CACHE_HIT = "orinuno; hit";
    private static final String CACHE_FALLBACK = "orinuno; fwd=miss; fallback";
    private static final String CACHE_BYPASS = "orinuno; fwd=bypass";

    private final JutsuClient jutsuClient;
    private final JutsuCatalogReadService readService;
    private final JutsuLiveFallbackService fallbackService;

    public JutsuApiController(
            JutsuClient jutsuClient,
            JutsuCatalogReadService readService,
            JutsuLiveFallbackService fallbackService) {
        this.jutsuClient = jutsuClient;
        this.readService = readService;
        this.fallbackService = fallbackService;
    }

    // -------------------------------------------------------------------------
    // Catalog browse / filter — cache-first
    // -------------------------------------------------------------------------

    @GetMapping("/catalog")
    @Operation(
            summary = "Browse the jut.su catalog with optional filters and sort (cache-first)",
            description =
                    "Reads from the L1 cache (JutsuCatalogSyncService) by default; falls back to a"
                        + " guarded live SDK call on cache-miss. Filter parameters accept either"
                        + " the URL slug (e.g. `action`, `before2000`, `order-by-name`) or the SDK"
                        + " enum name (e.g. `ACTION`, `BEFORE_2000`, `BY_NAME`). Both forms are"
                        + " equivalent — the response always echoes slugs. The Cache-Status header"
                        + " (RFC 9211) reports `hit` / `fwd=miss; fallback` so consumers can tell"
                        + " where the payload came from.")
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
        JutsuCatalogReadService.JutsuCatalogQuery query =
                new JutsuCatalogReadService.JutsuCatalogQuery(
                        page, filter.genres(), filter.types(), filter.years(), filter.sort());
        // Repository call is synchronous JDBC — wrap in fromCallable + boundedElastic to keep the
        // event loop free. The fallback path is already reactive (uses WebClient under the hood).
        return Mono.fromCallable(() -> readService.findCatalogPage(query))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(
                        cached -> {
                            if (cached.isPresent()) {
                                return Mono.just(ok(cached.get(), CACHE_HIT));
                            }
                            JutsuCatalogRequest request =
                                    JutsuCatalogRequest.filtered(filter, page);
                            return fallbackService
                                    .liveBrowseCatalog(request)
                                    .map(JutsuCatalogPageDto::from)
                                    .map(dto -> ok(dto, CACHE_FALLBACK))
                                    .onErrorResume(JutsuApiController::fallbackErrorTo503);
                        });
    }

    // -------------------------------------------------------------------------
    // Title search — bypasses cache by design
    // -------------------------------------------------------------------------

    @GetMapping("/search")
    @Operation(
            summary = "Search the jut.su catalog by title (live, NOT cached)",
            description =
                    "Sends the same POST as /catalog with the show_search form field populated."
                        + " Filter parameters mean exactly what they mean for /catalog and accept"
                        + " slug or enum-name input identically. Search is NOT served from the L1"
                        + " cache — text queries would multiply cache keys without benefit; every"
                        + " call hits jut.su via the live SDK. Response carries `Cache-Status:"
                        + " orinuno; fwd=bypass`.")
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
        return page$.map(JutsuCatalogPageDto::from).map(dto -> ok(dto, CACHE_BYPASS));
    }

    // -------------------------------------------------------------------------
    // Anime info page — cache-first
    // -------------------------------------------------------------------------

    @GetMapping("/anime/{slug}")
    @Operation(
            summary = "Fetch the anime info page (cache-first)",
            description =
                    "Returns the full season/episode listing, plus chrome metadata (title,"
                        + " synopsis, year, genres, types, thumbnail). The episode list is grouped"
                        + " by season; single-season anime collapse into one block. Cache-Status"
                        + " header reports `hit` / `fwd=miss; fallback`.")
    public Mono<ResponseEntity<JutsuAnimeInfoDto>> getAnimeInfo(
            @Parameter(
                            description = "Anime slug (the path segment used by jut.su URLs)",
                            example = "naruto",
                            required = true)
                    @PathVariable
                    String slug) {
        return Mono.fromCallable(() -> readService.findAnimeInfo(slug))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(
                        (Optional<JutsuAnimeInfoDto> cached) -> {
                            if (cached.isPresent()) {
                                return Mono.just(ok(cached.get(), CACHE_HIT));
                            }
                            return fallbackService
                                    .liveAnimeInfo(slug)
                                    .map(JutsuAnimeInfoDto::from)
                                    .map(dto -> ok(dto, CACHE_FALLBACK))
                                    .onErrorResume(JutsuApiController::fallbackErrorTo503);
                        });
    }

    // -------------------------------------------------------------------------
    // Single-episode metadata (no decode)
    // -------------------------------------------------------------------------

    @GetMapping("/episode")
    @Operation(
            summary = "Fetch one viewer page's chrome metadata without invoking the decoder",
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
    // Helpers
    // -------------------------------------------------------------------------

    /** Wrap a successful body in a 200 response with the given {@code Cache-Status} header. */
    private static <T> ResponseEntity<T> ok(T body, String cacheStatus) {
        HttpHeaders headers = new HttpHeaders();
        headers.add(CACHE_STATUS, cacheStatus);
        return ResponseEntity.ok().headers(headers).body(body);
    }

    /**
     * Map a fallback-path error to a 503 response with a diagnostic header explaining which guard
     * fired (or whether the live call itself errored). Keeps the body shape consistent with success
     * ({@code null} payload + {@code Cache-Status} header) so the demo UI can switch on status
     * without parsing the body.
     */
    private static <T> Mono<ResponseEntity<T>> fallbackErrorTo503(Throwable err) {
        String reason;
        if (err instanceof JutsuLiveFallbackService.FallbackDisabledException) {
            reason = "fallback-disabled";
        } else if (err instanceof JutsuLiveFallbackService.BreakerOpenException) {
            reason = "circuit-breaker-open";
        } else if (err instanceof JutsuLiveFallbackService.NegativeCacheHitException) {
            reason = "negative-cache-hit";
        } else {
            reason = "live-fetch-failed";
            log.warn("jutsu-fallback: live fetch failed for cache-miss request", err);
        }
        HttpHeaders headers = new HttpHeaders();
        headers.add(CACHE_STATUS, "orinuno; fwd=miss; fallback-error");
        headers.add(FALLBACK_REASON, reason);
        return Mono.just(
                ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).headers(headers).body(null));
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
