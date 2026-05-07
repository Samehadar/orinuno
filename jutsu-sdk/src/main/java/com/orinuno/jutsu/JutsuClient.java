package com.orinuno.jutsu;

import com.orinuno.jutsu.auth.JutsuSessionManager;
import com.orinuno.jutsu.catalog.JutsuCatalogClient;
import com.orinuno.jutsu.catalog.JutsuCatalogPage;
import com.orinuno.jutsu.catalog.JutsuCatalogRequest;
import com.orinuno.jutsu.decoder.JutsuDecoder;
import com.orinuno.jutsu.drift.JutsuDriftDetector;
import com.orinuno.jutsu.drift.JutsuDriftSnapshot;
import com.orinuno.jutsu.episode.JutsuEpisodeMeta;
import com.orinuno.jutsu.episode.JutsuEpisodeMetaClient;
import com.orinuno.jutsu.filter.JutsuCatalogFilter;
import com.orinuno.jutsu.info.JutsuAnimeInfo;
import com.orinuno.jutsu.info.JutsuAnimeInfoClient;
import com.orinuno.jutsu.notice.JutsuNoticeClient;
import com.orinuno.jutsu.notice.JutsuNoticeEntry;
import com.orinuno.jutsu.notice.JutsuNoticeFeed;
import com.orinuno.jutsu.ratelimit.JutsuRateLimiter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.Nullable;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Public facade for the JutSu (jut.su) SDK. Wraps the rate limiter, session manager, decoder,
 * catalog/info/episode/notice clients and drift detector behind one entry point so callers don't
 * have to know how the parts fit together.
 *
 * <p>Construct via {@link #builder()}:
 *
 * <pre>{@code
 * JutsuClient client = JutsuClient.builder()
 *         .config(JutsuConfig.builder()
 *                 .credentials(System.getenv("JUTSU_USERNAME"),
 *                              System.getenv("JUTSU_PASSWORD"))
 *                 .rateLimitRps(1.0)
 *                 .build())
 *         .build();
 *
 * // Decode a single episode (existing behaviour):
 * client.decode("https://jut.su/naruto/episode-1.html").subscribe(...);
 *
 * // Browse the catalog (new):
 * client.browseCatalog(JutsuCatalogRequest.allAnimeFirstPage()).subscribe(...);
 *
 * // Stream upcoming-release notices (new):
 * client.streamNoticeEntries(/* startCursor *&#47; 18729, /* maxFeeds *&#47; 5).subscribe(...);
 *
 * // Drift health, exposed for orinuno-app's MultiSourceRanker:
 * JutsuDriftSnapshot snapshot = client.getDriftSnapshot();
 * }</pre>
 *
 * <p>The SDK has no auto-configuration. Spring Boot consumers should wire it from a single
 * {@code @Configuration} class — orinuno-app does this in {@code JutsuSdkConfiguration}.
 */
public final class JutsuClient {

    private final JutsuConfig config;
    private final JutsuRateLimiter rateLimiter;
    private final JutsuSessionManager sessionManager;
    private final JutsuDecoder decoder;
    private final JutsuDriftDetector driftDetector;
    private final JutsuCatalogClient catalogClient;
    private final JutsuAnimeInfoClient animeInfoClient;
    private final JutsuEpisodeMetaClient episodeMetaClient;
    private final JutsuNoticeClient noticeClient;

    private JutsuClient(
            JutsuConfig config,
            JutsuRateLimiter rateLimiter,
            JutsuSessionManager sessionManager,
            JutsuDecoder decoder,
            JutsuDriftDetector driftDetector,
            JutsuCatalogClient catalogClient,
            JutsuAnimeInfoClient animeInfoClient,
            JutsuEpisodeMetaClient episodeMetaClient,
            JutsuNoticeClient noticeClient) {
        this.config = config;
        this.rateLimiter = rateLimiter;
        this.sessionManager = sessionManager;
        this.decoder = decoder;
        this.driftDetector = driftDetector;
        this.catalogClient = catalogClient;
        this.animeInfoClient = animeInfoClient;
        this.episodeMetaClient = episodeMetaClient;
        this.noticeClient = noticeClient;
    }

    public static Builder builder() {
        return new Builder();
    }

    // -------------------------------------------------------------------------
    // Decode (legacy)
    // -------------------------------------------------------------------------

    /** Decode a single jut.su episode URL into per-quality mp4 links. */
    public Mono<JutsuDecodeResult> decode(String episodeUrl) {
        return decoder.decode(episodeUrl);
    }

    // -------------------------------------------------------------------------
    // Catalog browse / search
    // -------------------------------------------------------------------------

    /**
     * Fetch one page of the anime catalog (POST {@code /anime/{path}/} with {@code
     * ajax_load=yes&start_from_page=N&show_search=&anime_of_user=}). 30 entries per page.
     */
    public Mono<JutsuCatalogPage> browseCatalog(JutsuCatalogRequest request) {
        return catalogClient.browse(request);
    }

    /** Convenience: page {@code page} of the unfiltered, newest-first catalog. */
    public Mono<JutsuCatalogPage> browseCatalog(int page) {
        return browseCatalog(JutsuCatalogRequest.unfiltered(page));
    }

    /**
     * Convenience: page {@code page} of the catalog with the given filter applied. Filter slug
     * composition is deterministic (declaration-order of the underlying enums) so requests are
     * cache-friendly.
     */
    public Mono<JutsuCatalogPage> browseCatalog(JutsuCatalogFilter filter, int page) {
        return browseCatalog(JutsuCatalogRequest.filtered(filter, page));
    }

    /**
     * Convenience: title-search the catalog. Equivalent to {@link
     * #browseCatalog(JutsuCatalogRequest)} with {@link JutsuCatalogRequest#search(String, int)}.
     * The result composes orthogonally with filters via {@link
     * JutsuCatalogRequest#searchInFilter(JutsuCatalogFilter, String, int)}.
     */
    public Mono<JutsuCatalogPage> searchByTitle(String query, int page) {
        return browseCatalog(JutsuCatalogRequest.search(query, page));
    }

    /** Title search composed with a filter. */
    public Mono<JutsuCatalogPage> searchByTitle(JutsuCatalogFilter filter, String query, int page) {
        return browseCatalog(JutsuCatalogRequest.searchInFilter(filter, query, page));
    }

    // -------------------------------------------------------------------------
    // Anime info
    // -------------------------------------------------------------------------

    /** Fetch the anime info page ({@code GET /{slug}/}). */
    public Mono<JutsuAnimeInfo> getAnimeInfo(String slug) {
        return animeInfoClient.getInfo(slug);
    }

    // -------------------------------------------------------------------------
    // Episode metadata (no decode)
    // -------------------------------------------------------------------------

    /**
     * Fetch lightweight metadata for a single episode page (title, prev/next links, paywall flag),
     * without invoking the heavy video-decode pipeline. Use {@link #decode(String)} when you need
     * the actual mp4 links.
     */
    public Mono<JutsuEpisodeMeta> getEpisodeMeta(String relativeOrAbsoluteUrl) {
        return episodeMetaClient.getMeta(relativeOrAbsoluteUrl);
    }

    // -------------------------------------------------------------------------
    // Notice feed
    // -------------------------------------------------------------------------

    /** Fetch one page of the upcoming-releases notice feed at the given cursor. */
    public Mono<JutsuNoticeFeed> getNoticeFeed(int noticeId) {
        return noticeClient.getFeed(noticeId);
    }

    /** Discover the freshest notice cursor and fetch the corresponding feed. */
    public Mono<JutsuNoticeFeed> getLatestNoticeFeed() {
        return noticeClient.getLatestFeed();
    }

    /**
     * Stream notice feeds page-by-page going backwards in history, stopping at the history bound or
     * {@code maxFeeds} pages (whichever comes first; non-positive {@code maxFeeds} disables the
     * cap).
     */
    public Flux<JutsuNoticeFeed> walkNoticeFeedsBackwards(int startNoticeId, int maxFeeds) {
        return noticeClient.walkFeedsBackwards(startNoticeId, maxFeeds);
    }

    /**
     * Stream individual notice entries going backwards in history. Equivalent to {@link
     * #walkNoticeFeedsBackwards(int, int)} flat-mapped on entries.
     */
    public Flux<JutsuNoticeEntry> streamNoticeEntries(int startNoticeId, int maxFeeds) {
        return noticeClient.streamEntries(startNoticeId, maxFeeds);
    }

    // -------------------------------------------------------------------------
    // Diagnostics
    // -------------------------------------------------------------------------

    /**
     * Snapshot of the SDK's drift detector. Exposed so orinuno-app's MultiSourceRanker can demote
     * jut.su when the SDK reports {@code DEGRADED} or {@code UNAVAILABLE} health, and so dashboards
     * can display the recent drift events.
     */
    public JutsuDriftSnapshot getDriftSnapshot() {
        return driftDetector.snapshot();
    }

    // -------------------------------------------------------------------------
    // Underlying components
    // -------------------------------------------------------------------------

    /** Snapshot of the configuration the client was built with. */
    public JutsuConfig config() {
        return config;
    }

    /**
     * The shared rate limiter. Exposed so adjacent components (e.g. a CDN pass-through proxy in
     * orinuno-app) can consume from the same RPS budget rather than spinning up a parallel limiter
     * and silently doubling the outbound rate.
     */
    public JutsuRateLimiter rateLimiter() {
        return rateLimiter;
    }

    /**
     * The shared session manager. Exposed so a CDN proxy can attach the cached cookie header to its
     * own requests — the upstream Yandex CDN URLs require the same session that produced them.
     */
    public JutsuSessionManager sessionManager() {
        return sessionManager;
    }

    /** The shared drift detector, for advanced consumers that want to wire alerts directly. */
    public JutsuDriftDetector driftDetector() {
        return driftDetector;
    }

    // -------------------------------------------------------------------------
    // Builder
    // -------------------------------------------------------------------------

    /**
     * Builder for {@link JutsuClient}. Pluggable so consumers can wire their own {@link
     * MeterRegistry} or pre-configured {@link WebClient.Builder} (e.g. for Wiremock-driven tests).
     */
    public static final class Builder {
        @Nullable private JutsuConfig config;
        @Nullable private MeterRegistry meterRegistry;
        @Nullable private WebClient.Builder webClientBuilder;
        @Nullable private JutsuDriftDetector driftDetector;
        @Nullable private JutsuRateLimiter rateLimiter;
        @Nullable private JutsuSessionManager sessionManager;

        private Builder() {}

        public Builder config(JutsuConfig config) {
            this.config = config;
            return this;
        }

        /** Optional — defaults to a no-op {@code SimpleMeterRegistry}. */
        public Builder meterRegistry(@Nullable MeterRegistry meterRegistry) {
            this.meterRegistry = meterRegistry;
            return this;
        }

        /** Optional — defaults to {@code WebClient.builder()}. */
        public Builder webClientBuilder(@Nullable WebClient.Builder webClientBuilder) {
            this.webClientBuilder = webClientBuilder;
            return this;
        }

        /**
         * Optional — defaults to a fresh per-client {@link JutsuDriftDetector}. Pass a shared
         * detector when you want multiple {@code JutsuClient} instances (e.g. one per Spring
         * profile) to feed the same drift dashboard.
         */
        public Builder driftDetector(@Nullable JutsuDriftDetector driftDetector) {
            this.driftDetector = driftDetector;
            return this;
        }

        /**
         * Optional — pass a pre-built {@link JutsuRateLimiter} when the surrounding application
         * already manages it as a singleton (e.g. an orinuno-app {@code @Bean}). The SDK's
         * sub-clients will share this bucket so the outbound RPS budget isn't doubled.
         */
        public Builder rateLimiter(@Nullable JutsuRateLimiter rateLimiter) {
            this.rateLimiter = rateLimiter;
            return this;
        }

        /**
         * Optional — pass a pre-built {@link JutsuSessionManager} when the surrounding application
         * already manages it as a singleton (e.g. orinuno-app's CDN proxy that needs the same
         * cookie jar). The SDK's sub-clients will reuse this manager so the cookie session is
         * coherent across endpoints.
         */
        public Builder sessionManager(@Nullable JutsuSessionManager sessionManager) {
            this.sessionManager = sessionManager;
            return this;
        }

        public JutsuClient build() {
            if (config == null) {
                throw new IllegalStateException("config is required — call .config(...) first");
            }
            WebClient.Builder builder =
                    webClientBuilder == null ? WebClient.builder() : webClientBuilder;
            JutsuRateLimiter limiter =
                    rateLimiter == null
                            ? new JutsuRateLimiter(config::rateLimitRps, meterRegistry)
                            : rateLimiter;
            JutsuSessionManager session =
                    sessionManager == null
                            ? new JutsuSessionManager(config, limiter, builder, meterRegistry)
                            : sessionManager;
            JutsuDriftDetector detector =
                    driftDetector == null ? new JutsuDriftDetector() : driftDetector;
            JutsuDecoder decoder = new JutsuDecoder(config, limiter, session, builder);
            // catalog calls intentionally fly anonymous — no sessionManager here.
            // See JutsuCatalogClient class javadoc for the rationale.
            JutsuCatalogClient catalogClient =
                    new JutsuCatalogClient(config, limiter, detector, builder);
            JutsuAnimeInfoClient animeInfoClient =
                    new JutsuAnimeInfoClient(config, limiter, session, detector, builder);
            JutsuEpisodeMetaClient episodeMetaClient =
                    new JutsuEpisodeMetaClient(config, limiter, session, detector, builder);
            JutsuNoticeClient noticeClient =
                    new JutsuNoticeClient(config, limiter, session, detector, builder);
            return new JutsuClient(
                    config,
                    limiter,
                    session,
                    decoder,
                    detector,
                    catalogClient,
                    animeInfoClient,
                    episodeMetaClient,
                    noticeClient);
        }
    }
}
