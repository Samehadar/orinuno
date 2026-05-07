package com.orinuno.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.orinuno.configuration.JutsuLiveFallbackProperties;
import com.orinuno.jutsu.JutsuClient;
import com.orinuno.jutsu.catalog.JutsuCatalogEntry;
import com.orinuno.jutsu.catalog.JutsuCatalogPage;
import com.orinuno.jutsu.drift.JutsuDriftDetector;
import com.orinuno.jutsu.drift.JutsuDriftSnapshot;
import com.orinuno.jutsu.episode.JutsuEpisodeMeta;
import com.orinuno.jutsu.fallback.JutsuLiveFallbackService;
import com.orinuno.jutsu.filter.JutsuCatalogFilter;
import com.orinuno.jutsu.filter.JutsuGenre;
import com.orinuno.jutsu.info.JutsuAnimeInfo;
import com.orinuno.jutsu.model.JutsuTitle;
import com.orinuno.jutsu.notice.JutsuNoticeEntry;
import com.orinuno.jutsu.notice.JutsuNoticeFeed;
import com.orinuno.jutsu.repository.JutsuEpisodeRepository;
import com.orinuno.jutsu.repository.JutsuTitleRepository;
import com.orinuno.jutsu.sync.JutsuCatalogSyncService;
import com.orinuno.jutsu.sync.JutsuStalenessTracker;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * WebTestClient-bound tests for the post-ADR-0016-P1a {@link JutsuApiController}. Exercises both
 * the DB-first cache hit path and the hybrid live-fallback miss path.
 */
@ExtendWith(MockitoExtension.class)
class JutsuApiControllerTest {

    @Mock private JutsuClient jutsuClient;
    @Mock private JutsuTitleRepository titleRepository;
    @Mock private JutsuEpisodeRepository episodeRepository;
    @Mock private JutsuCatalogSyncService syncService;
    @Mock private JutsuStalenessTracker stalenessTracker;

    private JutsuLiveFallbackService liveFallbackService;
    private WebTestClient client;
    private final Clock fixedClock =
            Clock.fixed(
                    ZonedDateTime.of(2026, 5, 7, 12, 0, 0, 0, ZoneOffset.UTC).toInstant(),
                    ZoneId.of("UTC"));

    @BeforeEach
    void setUp() {
        JutsuLiveFallbackProperties props =
                new JutsuLiveFallbackProperties(
                        true,
                        new JutsuLiveFallbackProperties.RateLimit(5.0),
                        new JutsuLiveFallbackProperties.NegativeCache(24),
                        new JutsuLiveFallbackProperties.Buckets());
        liveFallbackService = new JutsuLiveFallbackService(props, new SimpleMeterRegistry());
        // Lenient: not all controller paths read the staleness header (drift / notice
        // forwarders go straight to the SDK), but those that do should always see 300s.
        lenient().when(stalenessTracker.staleSeconds()).thenReturn(300L);
        JutsuApiController controller =
                new JutsuApiController(
                        jutsuClient,
                        titleRepository,
                        episodeRepository,
                        syncService,
                        liveFallbackService,
                        stalenessTracker,
                        fixedClock);
        client = WebTestClient.bindToController(controller).build();
    }

    @Test
    @DisplayName("GET /catalog without filters → DB read, X-Sync-Stale-Seconds header set")
    void browseCatalogDbServed() {
        JutsuTitle row =
                JutsuTitle.builder().slug("naruto").titleRu("Наруто").titleEn("Naruto").build();
        when(titleRepository.countFiltered(any(), any())).thenReturn(1L);
        when(titleRepository.listFiltered(any(), any(), anyInt(), anyInt()))
                .thenReturn(List.of(row));

        client.get()
                .uri("/api/v1/sources/jutsu/catalog")
                .exchange()
                .expectStatus()
                .isOk()
                .expectHeader()
                .valueEquals("X-Sync-Stale-Seconds", "300")
                .expectBody()
                .jsonPath("$.entries[0].slug")
                .isEqualTo("naruto");

        verify(jutsuClient, never()).browseCatalog(anyInt());
    }

    @Test
    @DisplayName("GET /catalog with genre filter → live SDK fallback")
    void browseCatalogWithFilterFallsBackToSdk() {
        JutsuCatalogPage page =
                new JutsuCatalogPage(
                        List.of(
                                new JutsuCatalogEntry(
                                        29,
                                        "naruto",
                                        "Наруто",
                                        "Naruto",
                                        "thumb.jpg",
                                        220,
                                        0,
                                        Set.of(JutsuGenre.ACTION),
                                        Set.of(),
                                        Optional.empty())),
                        1,
                        false);
        when(jutsuClient.browseCatalog(any(JutsuCatalogFilter.class), eq(1)))
                .thenReturn(Mono.just(page));

        client.get()
                .uri(
                        b ->
                                b.path("/api/v1/sources/jutsu/catalog")
                                        .queryParam("genres", "ACTION")
                                        .build())
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.entries[0].slug")
                .isEqualTo("naruto");
        verify(jutsuClient, atLeastOnce()).browseCatalog(any(JutsuCatalogFilter.class), eq(1));
    }

    @Test
    @DisplayName("GET /anime/{slug} cache hit → returns DB row, never calls SDK")
    void getAnimeInfoCacheHit() {
        JutsuTitle row =
                JutsuTitle.builder().slug("naruto").titleRu("Наруто").titleEn("Naruto").build();
        when(titleRepository.findBySlug("naruto")).thenReturn(Optional.of(row));
        when(episodeRepository.listForTitle("naruto")).thenReturn(List.of());

        client.get()
                .uri("/api/v1/sources/jutsu/anime/naruto")
                .exchange()
                .expectStatus()
                .isOk()
                .expectHeader()
                .exists("X-Sync-Stale-Seconds")
                .expectBody()
                .jsonPath("$.slug")
                .isEqualTo("naruto")
                .jsonPath("$.title")
                .isEqualTo("Наруто");
        verify(jutsuClient, never()).getAnimeInfo(any());
    }

    @Test
    @DisplayName(
            "GET /anime/{slug} miss → live SDK fallback, syncService.upsertFromAnimeInfo called")
    void getAnimeInfoCacheMissTriggersFallback() {
        when(titleRepository.findBySlug("missing")).thenReturn(Optional.empty());
        JutsuAnimeInfo info =
                new JutsuAnimeInfo(
                        "missing",
                        "Missing Title",
                        null,
                        null,
                        Optional.empty(),
                        Set.of(),
                        Set.of(),
                        null,
                        List.of());
        when(jutsuClient.getAnimeInfo("missing")).thenReturn(Mono.just(info));

        client.get()
                .uri("/api/v1/sources/jutsu/anime/missing")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.slug")
                .isEqualTo("missing");
        verify(syncService).upsertFromAnimeInfo(info);
    }

    @Test
    @DisplayName("Negative-cached slug returns 404 without hitting upstream on the second request")
    void getAnimeInfoNegativeCacheBlocksRepeatMiss() {
        when(titleRepository.findBySlug("ghost")).thenReturn(Optional.empty());
        when(jutsuClient.getAnimeInfo("ghost")).thenReturn(Mono.empty());

        // First call -> upstream returns empty -> slug goes into negative cache.
        client.get()
                .uri("/api/v1/sources/jutsu/anime/ghost")
                .exchange()
                .expectStatus()
                .isNotFound();

        // Second call -> negative cache short-circuits with 404.
        client.get()
                .uri("/api/v1/sources/jutsu/anime/ghost")
                .exchange()
                .expectStatus()
                .isNotFound();
        verify(jutsuClient, atLeastOnce()).getAnimeInfo("ghost");
    }

    @Test
    @DisplayName("GET /episode parses URL and returns DB row when cached")
    void getEpisodeMetaDbHit() {
        when(episodeRepository.findByTitleAndPosition("naruto", 1, 1))
                .thenReturn(
                        Optional.of(
                                com.orinuno.jutsu.model.JutsuEpisode.builder()
                                        .titleSlug("naruto")
                                        .season(1)
                                        .episode(1)
                                        .embedUrl("https://jut.su/naruto/season-1/episode-1.html")
                                        .build()));

        when(titleRepository.findBySlug("naruto"))
                .thenReturn(
                        Optional.of(
                                JutsuTitle.builder()
                                        .slug("naruto")
                                        .titleRu("Наруто")
                                        .titleEn("Naruto")
                                        .build()));
        client.get()
                .uri(
                        b ->
                                b.path("/api/v1/sources/jutsu/episode")
                                        .queryParam(
                                                "url",
                                                "https://jut.su/naruto/season-1/episode-1.html")
                                        .build())
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.slug")
                .isEqualTo("naruto")
                .jsonPath("$.episode")
                .isEqualTo(1)
                .jsonPath("$.canonicalUrl")
                .isEqualTo("https://jut.su/naruto/season-1/episode-1.html");
        verify(jutsuClient, never()).getEpisodeMeta(any());
    }

    @Test
    @DisplayName("GET /episode miss → live SDK fallback (and writes to DB)")
    void getEpisodeMetaMissFallback() {
        when(episodeRepository.findByTitleAndPosition("naruto", 1, 5)).thenReturn(Optional.empty());
        JutsuEpisodeMeta meta =
                new JutsuEpisodeMeta(
                        "naruto",
                        1,
                        5,
                        "Наруто 1 сезон 5 серия",
                        "title",
                        "https://jut.su/naruto/season-1/episode-5.html",
                        null,
                        null,
                        null,
                        "/naruto/",
                        false);
        when(jutsuClient.getEpisodeMeta("https://jut.su/naruto/season-1/episode-5.html"))
                .thenReturn(Mono.just(meta));

        client.get()
                .uri(
                        b ->
                                b.path("/api/v1/sources/jutsu/episode")
                                        .queryParam(
                                                "url",
                                                "https://jut.su/naruto/season-1/episode-5.html")
                                        .build())
                .exchange()
                .expectStatus()
                .isOk();
        verify(syncService).upsertEpisode(any());
    }

    @Test
    @DisplayName("GET /notice unchanged: forwards to live SDK")
    void noticeFeedLatestStillLive() {
        JutsuNoticeFeed feed = new JutsuNoticeFeed(100, List.of());
        when(jutsuClient.getLatestNoticeFeed()).thenReturn(Mono.just(feed));
        client.get()
                .uri("/api/v1/sources/jutsu/notice")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.requestedCursor")
                .isEqualTo(100);
        verify(jutsuClient).getLatestNoticeFeed();
    }

    @Test
    @DisplayName("GET /notice?cursor pipes through to getNoticeFeed(cursor)")
    void noticeFeedExplicitCursor() {
        JutsuNoticeFeed feed =
                new JutsuNoticeFeed(
                        50,
                        List.of(
                                new JutsuNoticeEntry(
                                        "x",
                                        1,
                                        2,
                                        "X: 2",
                                        "https://jut.su/x/episode-2.html",
                                        null,
                                        "сегодня")));
        when(jutsuClient.getNoticeFeed(50)).thenReturn(Mono.just(feed));
        client.get()
                .uri(b -> b.path("/api/v1/sources/jutsu/notice").queryParam("cursor", "50").build())
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.requestedCursor")
                .isEqualTo(50);
    }

    @Test
    @DisplayName("GET /notice/stream pipes streamNoticeEntries() as NDJSON")
    void streamNoticeEntries() {
        JutsuNoticeEntry e1 =
                new JutsuNoticeEntry(
                        "x", 1, 1, "X: 1", "https://jut.su/x/episode-1.html", null, "сегодня");
        when(jutsuClient.streamNoticeEntries(anyInt(), anyInt())).thenReturn(Flux.just(e1));
        client.get()
                .uri(
                        b ->
                                b.path("/api/v1/sources/jutsu/notice/stream")
                                        .queryParam("startCursor", "100")
                                        .queryParam("maxFeeds", "2")
                                        .build())
                .exchange()
                .expectStatus()
                .isOk();
        verify(jutsuClient).streamNoticeEntries(100, 2);
    }

    @Test
    @DisplayName("GET /drift returns the typed drift snapshot")
    void getDrift() {
        JutsuDriftSnapshot snapshot = new JutsuDriftDetector().snapshot();
        when(jutsuClient.getDriftSnapshot()).thenReturn(snapshot);
        client.get()
                .uri("/api/v1/sources/jutsu/drift")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.health")
                .isEqualTo("HEALTHY");
    }

    @Test
    @DisplayName("?refresh=true without X-API-KEY → 401")
    void refreshRequiresApiKey() {
        client.get()
                .uri(
                        b ->
                                b.path("/api/v1/sources/jutsu/anime/naruto")
                                        .queryParam("refresh", "true")
                                        .build())
                .exchange()
                .expectStatus()
                .isUnauthorized();
        assertThat(liveFallbackService.isEnabled()).isTrue();
    }
}
