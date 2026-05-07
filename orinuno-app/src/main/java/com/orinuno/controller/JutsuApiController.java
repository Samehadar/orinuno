package com.orinuno.controller;

import com.orinuno.jutsu.JutsuClient;
import com.orinuno.jutsu.fallback.JutsuLiveFallbackException;
import com.orinuno.jutsu.fallback.JutsuLiveFallbackService;
import com.orinuno.jutsu.filter.JutsuCatalogFilter;
import com.orinuno.jutsu.filter.JutsuGenre;
import com.orinuno.jutsu.filter.JutsuSort;
import com.orinuno.jutsu.filter.JutsuType;
import com.orinuno.jutsu.filter.JutsuYear;
import com.orinuno.jutsu.model.JutsuEpisode;
import com.orinuno.jutsu.model.JutsuTitle;
import com.orinuno.jutsu.repository.JutsuEpisodeRepository;
import com.orinuno.jutsu.repository.JutsuTitleRepository;
import com.orinuno.jutsu.sync.JutsuCatalogSyncService;
import com.orinuno.jutsu.sync.JutsuStalenessTracker;
import com.orinuno.model.dto.jutsu.JutsuAnimeInfoDto;
import com.orinuno.model.dto.jutsu.JutsuCatalogPageDto;
import com.orinuno.model.dto.jutsu.JutsuDriftSnapshotDto;
import com.orinuno.model.dto.jutsu.JutsuEpisodeMetaDto;
import com.orinuno.model.dto.jutsu.JutsuNoticeFeedDto;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Nullable;
import java.net.InetSocketAddress;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * REST surface for the jut.su SDK. ADR 0016 P1a turned the catalog / search / anime / episode
 * endpoints into DB-first reads with hybrid live-fallback. Notice + drift remain live.
 *
 * <p>Auth is intentionally NOT routed through {@code ApiKeyAuthFilter} for backwards-compat with
 * the demo UI; live-fallback nonetheless requires a non-anonymous {@code X-API-KEY} for the {@code
 * ?refresh=true} branch (see ADR 0016 §"Force-refresh").
 *
 * <p>The controller is fully reactive: blocking DB reads are wrapped in {@code
 * Mono.fromCallable(...).subscribeOn(Schedulers.boundedElastic())} so the WebFlux event loop stays
 * unblocked. Live-fallback dispatching happens via {@link
 * JutsuLiveFallbackService#dispatchReactive(String, String, boolean, String,
 * java.util.function.Supplier)}.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/sources/jutsu")
@Tag(name = "JutSu", description = "jut.su catalog / info / notice / drift surface")
public class JutsuApiController {

    static final String SYNC_STALE_HEADER = "X-Sync-Stale-Seconds";
    static final String API_KEY_HEADER = "X-API-KEY";

    private static final Pattern EPISODE_URL_PATTERN =
            Pattern.compile("/([a-z0-9-]+)/(?:season-(\\d+)/)?episode-(\\d+)\\.html");
    private static final int DEFAULT_PAGE_SIZE = 30;
    private static final int MAX_PAGE_SIZE = 100;

    private final JutsuClient jutsuClient;
    private final JutsuTitleRepository titleRepository;
    private final JutsuEpisodeRepository episodeRepository;
    private final JutsuCatalogSyncService syncService;
    private final JutsuLiveFallbackService liveFallbackService;
    private final JutsuStalenessTracker stalenessTracker;
    private final Clock clock;

    public JutsuApiController(
            JutsuClient jutsuClient,
            JutsuTitleRepository titleRepository,
            JutsuEpisodeRepository episodeRepository,
            JutsuCatalogSyncService syncService,
            JutsuLiveFallbackService liveFallbackService,
            JutsuStalenessTracker stalenessTracker,
            Clock clock) {
        this.jutsuClient = jutsuClient;
        this.titleRepository = titleRepository;
        this.episodeRepository = episodeRepository;
        this.syncService = syncService;
        this.liveFallbackService = liveFallbackService;
        this.stalenessTracker = stalenessTracker;
        this.clock = clock;
    }

    // -------------------------------------------------------------------------
    // Catalog browse — DB-first; falls back to live SDK ONLY when the filter
    // combination is unsupported by the L1 mirror.
    // -------------------------------------------------------------------------

    @GetMapping("/catalog")
    @Operation(
            summary = "Browse the jut.su L1 catalog (paged)",
            description =
                    "DB-first read against jutsu_title with title/status filters. Filter shapes"
                        + " (genres/types/years/sort) are NOT mirrored in the L1 cache; when any of"
                        + " those is supplied the controller falls back to"
                        + " JutsuClient.browseCatalog under the live-fallback guards. Adds"
                        + " X-Sync-Stale-Seconds.")
    public Mono<ResponseEntity<?>> browseCatalog(
            @Parameter(description = "1-based page index", example = "1")
                    @RequestParam(defaultValue = "1")
                    int page,
            @Parameter(description = "Page size for DB read", example = "30")
                    @RequestParam(defaultValue = "30")
                    int pageSize,
            @Parameter(
                            description =
                                    "Filter title_ru / title_en LIKE this fragment (DB read"
                                            + " supports it directly).")
                    @RequestParam(required = false)
                    @Nullable
                    String titleQuery,
            @Parameter(
                            description =
                                    "Filter by jutsu_title.status (ongoing|released). Other"
                                            + " filter dimensions force the live-fallback path.")
                    @RequestParam(required = false)
                    @Nullable
                    String status,
            @RequestParam(required = false) @Nullable List<String> genres,
            @RequestParam(required = false) @Nullable List<String> types,
            @RequestParam(required = false) @Nullable List<String> years,
            @RequestParam(required = false) @Nullable String sort,
            ServerHttpRequest request) {
        boolean dbServable =
                (genres == null || genres.isEmpty())
                        && (types == null || types.isEmpty())
                        && (years == null || years.isEmpty())
                        && (sort == null || sort.isBlank());
        if (!dbServable) {
            JutsuCatalogFilter filter = buildFilter(genres, types, years, sort);
            String consumerKey = consumerKey(request);
            String apiKey = request.getHeaders().getFirst(API_KEY_HEADER);
            return liveFallbackService
                    .dispatchReactive(
                            "__catalog_filtered__",
                            consumerKey,
                            false,
                            apiKey,
                            () ->
                                    jutsuClient
                                            .browseCatalog(filter, page)
                                            .map(JutsuCatalogPageDto::from))
                    .map(opt -> opt.orElseGet(() -> emptyCatalog(page)))
                    .<ResponseEntity<?>>map(this::okWithStale)
                    .onErrorResume(JutsuLiveFallbackException.class, this::errorMono);
        }
        int effectivePageSize = clampPageSize(pageSize);
        int offset = Math.max(0, (page - 1) * effectivePageSize);
        return Mono.fromCallable(
                        () -> {
                            long total =
                                    titleRepository.countFiltered(
                                            emptyToNull(titleQuery), emptyToNull(status));
                            List<JutsuTitle> rows =
                                    titleRepository.listFiltered(
                                            emptyToNull(titleQuery),
                                            emptyToNull(status),
                                            effectivePageSize,
                                            offset);
                            return JutsuCatalogPageDto.fromTitlePage(
                                    page, effectivePageSize, total, rows);
                        })
                .subscribeOn(Schedulers.boundedElastic())
                .<ResponseEntity<?>>map(this::okWithStale);
    }

    // -------------------------------------------------------------------------
    // Title search — DB by default; ?refresh=true forces SDK.
    // -------------------------------------------------------------------------

    @GetMapping("/search")
    @Operation(
            summary = "Search the jut.su L1 catalog by title",
            description =
                    "DB-first LIKE search on title_ru / title_en. ?refresh=true forces a live SDK"
                            + " call (rate-limited, requires non-anonymous X-API-KEY).")
    public Mono<ResponseEntity<?>> searchCatalog(
            @Parameter(required = true) @RequestParam("q") String query,
            @Parameter(description = "1-based page index", example = "1")
                    @RequestParam(defaultValue = "1")
                    int page,
            @Parameter(description = "Page size", example = "30") @RequestParam(defaultValue = "30")
                    int pageSize,
            @Parameter(description = "Bypass DB, force a live SDK call")
                    @RequestParam(defaultValue = "false")
                    boolean refresh,
            ServerHttpRequest request) {
        if (refresh) {
            String consumerKey = consumerKey(request);
            String apiKey = request.getHeaders().getFirst(API_KEY_HEADER);
            return liveFallbackService
                    .dispatchReactive(
                            "__search:" + query,
                            consumerKey,
                            true,
                            apiKey,
                            () ->
                                    jutsuClient
                                            .searchByTitle(query, page)
                                            .map(JutsuCatalogPageDto::from))
                    .map(opt -> opt.orElseGet(() -> emptyCatalog(page)))
                    .<ResponseEntity<?>>map(this::okWithStale)
                    .onErrorResume(JutsuLiveFallbackException.class, this::errorMono);
        }
        int effectivePageSize = clampPageSize(pageSize);
        int offset = Math.max(0, (page - 1) * effectivePageSize);
        return Mono.fromCallable(
                        () -> {
                            long total = titleRepository.countFiltered(query, null);
                            List<JutsuTitle> rows =
                                    titleRepository.listFiltered(
                                            query, null, effectivePageSize, offset);
                            return JutsuCatalogPageDto.fromTitlePage(
                                    page, effectivePageSize, total, rows);
                        })
                .subscribeOn(Schedulers.boundedElastic())
                .<ResponseEntity<?>>map(this::okWithStale);
    }

    // -------------------------------------------------------------------------
    // Anime info — DB-first; on miss → hybrid-fallback to JutsuClient.getAnimeInfo
    // -------------------------------------------------------------------------

    @GetMapping("/anime/{slug}")
    @Operation(
            summary = "Fetch anime metadata + episodes from the L1 cache",
            description =
                    "DB-first; cache miss triggers JutsuClient.getAnimeInfo via the hybrid"
                            + " live-fallback (rate limit + negative cache + kill-switch). Adds"
                            + " X-Sync-Stale-Seconds.")
    public Mono<ResponseEntity<?>> getAnimeInfo(
            @PathVariable String slug,
            @Parameter(description = "Force a live SDK call (rate-limited, requires X-API-KEY)")
                    @RequestParam(defaultValue = "false")
                    boolean refresh,
            ServerHttpRequest request) {
        Mono<Optional<JutsuAnimeInfoDto>> dbHit =
                refresh
                        ? Mono.just(Optional.empty())
                        : Mono.fromCallable(
                                        () -> {
                                            Optional<JutsuTitle> hit =
                                                    titleRepository.findBySlug(slug);
                                            if (hit.isEmpty())
                                                return Optional.<JutsuAnimeInfoDto>empty();
                                            List<JutsuEpisode> episodes =
                                                    episodeRepository.listForTitle(slug);
                                            return Optional.of(
                                                    JutsuAnimeInfoDto.fromTitleWithEpisodes(
                                                            hit.get(), episodes));
                                        })
                                .subscribeOn(Schedulers.boundedElastic());
        return dbHit.flatMap(
                cached -> {
                    if (cached.isPresent()) {
                        return Mono.just(okWithStale(cached.get()));
                    }
                    String consumerKey = consumerKey(request);
                    String apiKey = request.getHeaders().getFirst(API_KEY_HEADER);
                    return liveFallbackService
                            .dispatchReactive(
                                    slug,
                                    consumerKey,
                                    refresh,
                                    apiKey,
                                    () ->
                                            jutsuClient
                                                    .getAnimeInfo(slug)
                                                    .flatMap(
                                                            info ->
                                                                    Mono.fromCallable(
                                                                                    () -> {
                                                                                        syncService
                                                                                                .upsertFromAnimeInfo(
                                                                                                        info);
                                                                                        return JutsuAnimeInfoDto
                                                                                                .from(
                                                                                                        info);
                                                                                    })
                                                                            .subscribeOn(
                                                                                    Schedulers
                                                                                            .boundedElastic())))
                            .<ResponseEntity<?>>map(
                                    opt -> {
                                        if (opt.isEmpty()) {
                                            return staleEntity(ResponseEntity.notFound().build());
                                        }
                                        return okWithStale(opt.get());
                                    })
                            .onErrorResume(JutsuLiveFallbackException.class, this::errorMono);
                });
    }

    // -------------------------------------------------------------------------
    // Episode meta — DB-first; on miss → hybrid-fallback to getEpisodeMeta
    // -------------------------------------------------------------------------

    @GetMapping("/episode")
    @Operation(
            summary = "Fetch one episode's metadata from the L1 cache",
            description =
                    "DB-first; cache miss triggers JutsuClient.getEpisodeMeta via the hybrid"
                            + " live-fallback. Adds X-Sync-Stale-Seconds.")
    public Mono<ResponseEntity<?>> getEpisodeMeta(
            @Parameter(required = true) @RequestParam String url,
            @Parameter(description = "Force a live SDK call (rate-limited, requires X-API-KEY)")
                    @RequestParam(defaultValue = "false")
                    boolean refresh,
            ServerHttpRequest request) {
        Optional<EpisodeCoordinates> coords = parseEpisodeUrl(url);
        if (coords.isEmpty()) {
            return Mono.just(ResponseEntity.badRequest().build());
        }
        EpisodeCoordinates c = coords.get();
        Mono<Optional<JutsuEpisodeMetaDto>> dbHit =
                refresh
                        ? Mono.just(Optional.empty())
                        : Mono.fromCallable(
                                        () ->
                                                episodeRepository
                                                        .findByTitleAndPosition(
                                                                c.slug(), c.season(), c.episode())
                                                        .map(
                                                                e ->
                                                                        JutsuEpisodeMetaDto
                                                                                .fromStored(
                                                                                        e,
                                                                                        titleRepository
                                                                                                .findBySlug(
                                                                                                        c
                                                                                                                .slug())
                                                                                                .orElse(
                                                                                                        null))))
                                .subscribeOn(Schedulers.boundedElastic());
        return dbHit.flatMap(
                cached -> {
                    if (cached.isPresent()) {
                        return Mono.just(okWithStale(cached.get()));
                    }
                    String consumerKey = consumerKey(request);
                    String apiKey = request.getHeaders().getFirst(API_KEY_HEADER);
                    return liveFallbackService
                            .dispatchReactive(
                                    c.slug(),
                                    consumerKey,
                                    refresh,
                                    apiKey,
                                    () ->
                                            jutsuClient
                                                    .getEpisodeMeta(url)
                                                    .flatMap(
                                                            meta ->
                                                                    Mono.fromCallable(
                                                                                    () -> {
                                                                                        syncService
                                                                                                .upsertEpisode(
                                                                                                        JutsuEpisode
                                                                                                                .builder()
                                                                                                                .titleSlug(
                                                                                                                        meta
                                                                                                                                .slug())
                                                                                                                .season(
                                                                                                                        meta
                                                                                                                                .season())
                                                                                                                .episode(
                                                                                                                        meta
                                                                                                                                .episode())
                                                                                                                .embedUrl(
                                                                                                                        meta
                                                                                                                                .canonicalUrl())
                                                                                                                .lastSyncedAt(
                                                                                                                        now())
                                                                                                                .build());
                                                                                        return JutsuEpisodeMetaDto
                                                                                                .from(
                                                                                                        meta);
                                                                                    })
                                                                            .subscribeOn(
                                                                                    Schedulers
                                                                                            .boundedElastic())))
                            .<ResponseEntity<?>>map(
                                    opt -> {
                                        if (opt.isEmpty()) {
                                            return staleEntity(ResponseEntity.notFound().build());
                                        }
                                        return okWithStale(opt.get());
                                    })
                            .onErrorResume(JutsuLiveFallbackException.class, this::errorMono);
                });
    }

    // -------------------------------------------------------------------------
    // Notice feed — UNCHANGED (notice IS the change-feed; no L1 caching makes sense)
    // -------------------------------------------------------------------------

    @GetMapping("/notice")
    @Operation(summary = "Fetch one page of the upcoming-releases notice feed (live SDK)")
    public Mono<ResponseEntity<JutsuNoticeFeedDto>> getNoticeFeed(
            @RequestParam(required = false) @Nullable Integer cursor) {
        Mono<com.orinuno.jutsu.notice.JutsuNoticeFeed> source =
                cursor == null
                        ? jutsuClient.getLatestNoticeFeed()
                        : jutsuClient.getNoticeFeed(cursor);
        return source.map(JutsuNoticeFeedDto::from).map(ResponseEntity::ok);
    }

    @GetMapping(value = "/notice/stream", produces = "application/x-ndjson")
    @Operation(summary = "Walk notice feeds backwards as NDJSON (live SDK)")
    public Flux<JutsuNoticeFeedDto.JutsuNoticeEntryDto> streamNoticeEntries(
            @RequestParam int startCursor, @RequestParam(defaultValue = "5") int maxFeeds) {
        return jutsuClient
                .streamNoticeEntries(startCursor, maxFeeds)
                .map(JutsuNoticeFeedDto.JutsuNoticeEntryDto::from);
    }

    // -------------------------------------------------------------------------
    // Drift — UNCHANGED
    // -------------------------------------------------------------------------

    @GetMapping(value = "/drift", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Read the current jut.su SDK drift snapshot")
    public ResponseEntity<JutsuDriftSnapshotDto> getDrift() {
        return ResponseEntity.ok(JutsuDriftSnapshotDto.from(jutsuClient.getDriftSnapshot()));
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private ResponseEntity<?> okWithStale(Object body) {
        long stale = stalenessTracker.staleSeconds();
        HttpHeaders headers = new HttpHeaders();
        headers.set(SYNC_STALE_HEADER, Long.toString(stale));
        return ResponseEntity.ok().headers(headers).body(body);
    }

    private ResponseEntity<?> staleEntity(ResponseEntity<?> response) {
        long stale = stalenessTracker.staleSeconds();
        HttpHeaders headers = new HttpHeaders();
        headers.addAll(response.getHeaders());
        headers.set(SYNC_STALE_HEADER, Long.toString(stale));
        return ResponseEntity.status(response.getStatusCode())
                .headers(headers)
                .body(response.getBody());
    }

    @SuppressWarnings("unchecked")
    private <T> Mono<ResponseEntity<T>> errorMono(JutsuLiveFallbackException ex) {
        return Mono.just((ResponseEntity<T>) toErrorResponse(ex));
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneId.systemDefault());
    }

    private static String consumerKey(ServerHttpRequest request) {
        String apiKey = request.getHeaders().getFirst(API_KEY_HEADER);
        if (apiKey != null && !apiKey.isBlank()) return "key:" + apiKey;
        // X-Forwarded-For is honoured by a global ForwardedHeaderTransformer when configured;
        // here we only use the local socket as a last resort. TD-JUTSU-XFF tracks adding a
        // first-class XFF-aware extractor when the gateway in front of orinuno is finalised.
        InetSocketAddress remote = request.getRemoteAddress();
        if (remote != null && remote.getAddress() != null) {
            return "ip:" + remote.getAddress().getHostAddress();
        }
        return "anon";
    }

    private static int clampPageSize(int requested) {
        if (requested <= 0) return DEFAULT_PAGE_SIZE;
        return Math.min(MAX_PAGE_SIZE, requested);
    }

    @Nullable
    private static String emptyToNull(@Nullable String s) {
        return s == null || s.isBlank() ? null : s;
    }

    private static Optional<EpisodeCoordinates> parseEpisodeUrl(String url) {
        if (url == null || url.isBlank()) return Optional.empty();
        Matcher m = EPISODE_URL_PATTERN.matcher(url);
        if (!m.find()) return Optional.empty();
        String slug = m.group(1);
        int season = m.group(2) == null ? 1 : Integer.parseInt(m.group(2));
        int episode = Integer.parseInt(m.group(3));
        return Optional.of(new EpisodeCoordinates(slug, season, episode));
    }

    private static JutsuCatalogPageDto emptyCatalog(int page) {
        return new JutsuCatalogPageDto(page, List.of(), false);
    }

    private static ResponseEntity<Object> toErrorResponse(JutsuLiveFallbackException ex) {
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(ex.status());
        if (ex.retryAfterSeconds() > 0) {
            builder =
                    builder.header(HttpHeaders.RETRY_AFTER, Long.toString(ex.retryAfterSeconds()));
        }
        return builder.body(java.util.Map.of("error", ex.getMessage()));
    }

    private record EpisodeCoordinates(String slug, int season, int episode) {}

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
