package com.orinuno.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.orinuno.jutsu.JutsuClient;
import com.orinuno.jutsu.catalog.JutsuCatalogEntry;
import com.orinuno.jutsu.catalog.JutsuCatalogPage;
import com.orinuno.jutsu.catalog.JutsuCatalogRequest;
import com.orinuno.jutsu.drift.JutsuDriftDetector;
import com.orinuno.jutsu.drift.JutsuDriftSnapshot;
import com.orinuno.jutsu.episode.JutsuEpisodeMeta;
import com.orinuno.jutsu.fallback.JutsuFallbackCircuitBreaker;
import com.orinuno.jutsu.fallback.JutsuLiveFallbackService;
import com.orinuno.jutsu.filter.JutsuCatalogFilter;
import com.orinuno.jutsu.filter.JutsuGenre;
import com.orinuno.jutsu.filter.JutsuType;
import com.orinuno.jutsu.filter.JutsuYear;
import com.orinuno.jutsu.info.JutsuAnimeInfo;
import com.orinuno.jutsu.notice.JutsuNoticeEntry;
import com.orinuno.jutsu.notice.JutsuNoticeFeed;
import com.orinuno.jutsu.read.JutsuCatalogReadService;
import com.orinuno.model.dto.jutsu.JutsuAnimeInfoDto;
import com.orinuno.model.dto.jutsu.JutsuCatalogEntryDto;
import com.orinuno.model.dto.jutsu.JutsuCatalogPageDto;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@ExtendWith(MockitoExtension.class)
class JutsuApiControllerTest {

    @Mock private JutsuClient jutsuClient;
    @Mock private JutsuCatalogReadService readService;
    @Mock private JutsuLiveFallbackService fallbackService;

    private WebTestClient client;

    @BeforeEach
    void setUp() {
        JutsuApiController controller =
                new JutsuApiController(jutsuClient, readService, fallbackService);
        client = WebTestClient.bindToController(controller).build();
    }

    // -------------------------------------------------------------------------
    // /catalog — cache-first
    // -------------------------------------------------------------------------

    @Test
    @DisplayName(
            "GET /catalog cache-hit: response comes from L1, Cache-Status=hit, fallback NOT"
                    + " invoked")
    void catalogCacheHit() {
        JutsuCatalogPageDto cached =
                new JutsuCatalogPageDto(
                        2,
                        List.of(
                                new JutsuCatalogEntryDto(
                                        "naruto",
                                        "Наруто",
                                        "Naruto",
                                        "thumb.jpg",
                                        220,
                                        0,
                                        List.of("action"),
                                        List.of(),
                                        null,
                                        "https://jut.su/naruto/")),
                        true);
        when(readService.findCatalogPage(any())).thenReturn(Optional.of(cached));

        client.get()
                .uri(
                        b ->
                                b.path("/api/v1/sources/jutsu/catalog")
                                        .queryParam("page", "2")
                                        .queryParam("genres", "ACTION")
                                        .build())
                .exchange()
                .expectStatus()
                .isOk()
                .expectHeader()
                .valueEquals("Cache-Status", "orinuno; hit")
                .expectBody()
                .jsonPath("$.page")
                .isEqualTo(2)
                .jsonPath("$.entries[0].slug")
                .isEqualTo("naruto");

        verify(fallbackService, never()).liveBrowseCatalog(any());
    }

    @Test
    @DisplayName(
            "GET /catalog cache-miss: read service returns empty, fallback wins; Cache-Status="
                    + " fwd=miss; fallback")
    void catalogCacheMissFallsBackToLive() {
        when(readService.findCatalogPage(any())).thenReturn(Optional.empty());
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
                        2,
                        true);
        when(fallbackService.liveBrowseCatalog(any(JutsuCatalogRequest.class)))
                .thenReturn(Mono.just(page));

        client.get()
                .uri(
                        b ->
                                b.path("/api/v1/sources/jutsu/catalog")
                                        .queryParam("page", "2")
                                        .queryParam("genres", "ACTION")
                                        .queryParam("types", "SHONEN")
                                        .queryParam("years", "Y_2024")
                                        .queryParam("sort", "BY_NAME")
                                        .build())
                .exchange()
                .expectStatus()
                .isOk()
                .expectHeader()
                .valueEquals("Cache-Status", "orinuno; fwd=miss; fallback")
                .expectBody()
                .jsonPath("$.page")
                .isEqualTo(2)
                .jsonPath("$.entries[0].slug")
                .isEqualTo("naruto");

        ArgumentCaptor<JutsuCatalogRequest> captor =
                ArgumentCaptor.forClass(JutsuCatalogRequest.class);
        verify(fallbackService).liveBrowseCatalog(captor.capture());
        JutsuCatalogFilter f = captor.getValue().filter();
        assertThat(f.genres()).containsExactly(JutsuGenre.ACTION);
        assertThat(f.types()).containsExactly(JutsuType.SHONEN);
        assertThat(f.years()).containsExactly(JutsuYear.Y_2024);
    }

    @Test
    @DisplayName(
            "GET /catalog cache-miss + breaker open: 503 with X-Orinuno-Fallback-Reason="
                    + " circuit-breaker-open")
    void catalogCacheMissBreakerOpen() {
        when(readService.findCatalogPage(any())).thenReturn(Optional.empty());
        when(fallbackService.liveBrowseCatalog(any(JutsuCatalogRequest.class)))
                .thenReturn(
                        Mono.error(
                                new JutsuLiveFallbackService.BreakerOpenException(
                                        JutsuFallbackCircuitBreaker.State.OPEN)));

        client.get()
                .uri("/api/v1/sources/jutsu/catalog")
                .exchange()
                .expectStatus()
                .isEqualTo(503)
                .expectHeader()
                .valueEquals("Cache-Status", "orinuno; fwd=miss; fallback-error")
                .expectHeader()
                .valueEquals("X-Orinuno-Fallback-Reason", "circuit-breaker-open");
    }

    @Test
    @DisplayName(
            "GET /catalog cache-miss + fallback disabled: 503 with X-Orinuno-Fallback-Reason="
                    + " fallback-disabled")
    void catalogCacheMissFallbackDisabled() {
        when(readService.findCatalogPage(any())).thenReturn(Optional.empty());
        when(fallbackService.liveBrowseCatalog(any(JutsuCatalogRequest.class)))
                .thenReturn(Mono.error(new JutsuLiveFallbackService.FallbackDisabledException()));

        client.get()
                .uri("/api/v1/sources/jutsu/catalog")
                .exchange()
                .expectStatus()
                .isEqualTo(503)
                .expectHeader()
                .valueEquals("X-Orinuno-Fallback-Reason", "fallback-disabled");
    }

    @Test
    @DisplayName(
            "GET /catalog cache-miss + neg-cache hit: 503 with X-Orinuno-Fallback-Reason="
                    + " negative-cache-hit")
    void catalogCacheMissNegativeCacheHit() {
        when(readService.findCatalogPage(any())).thenReturn(Optional.empty());
        when(fallbackService.liveBrowseCatalog(any(JutsuCatalogRequest.class)))
                .thenReturn(
                        Mono.error(
                                new JutsuLiveFallbackService.NegativeCacheHitException(
                                        "catalog:/anime/:1:")));

        client.get()
                .uri("/api/v1/sources/jutsu/catalog")
                .exchange()
                .expectStatus()
                .isEqualTo(503)
                .expectHeader()
                .valueEquals("X-Orinuno-Fallback-Reason", "negative-cache-hit");
    }

    @Test
    @DisplayName(
            "GET /catalog cache-miss + live SDK error: 503 with X-Orinuno-Fallback-Reason="
                    + " live-fetch-failed")
    void catalogCacheMissLiveFetchFails() {
        when(readService.findCatalogPage(any())).thenReturn(Optional.empty());
        when(fallbackService.liveBrowseCatalog(any(JutsuCatalogRequest.class)))
                .thenReturn(Mono.error(new RuntimeException("upstream 503")));

        client.get()
                .uri("/api/v1/sources/jutsu/catalog")
                .exchange()
                .expectStatus()
                .isEqualTo(503)
                .expectHeader()
                .valueEquals("X-Orinuno-Fallback-Reason", "live-fetch-failed");
    }

    // -------------------------------------------------------------------------
    // /anime/{slug} — cache-first
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("GET /anime/{slug} cache-hit: response comes from L1, Cache-Status=hit")
    void animeInfoCacheHit() {
        JutsuAnimeInfoDto cached =
                new JutsuAnimeInfoDto(
                        "naruto",
                        "Наруто",
                        "Naruto",
                        "synopsis",
                        "thumb",
                        "before2000",
                        List.of(2002, 2007),
                        "16+",
                        List.of("action"),
                        List.of(),
                        List.of(),
                        220);
        when(readService.findAnimeInfo("naruto")).thenReturn(Optional.of(cached));

        client.get()
                .uri("/api/v1/sources/jutsu/anime/naruto")
                .exchange()
                .expectStatus()
                .isOk()
                .expectHeader()
                .valueEquals("Cache-Status", "orinuno; hit")
                .expectBody()
                .jsonPath("$.slug")
                .isEqualTo("naruto")
                .jsonPath("$.totalEpisodeCount")
                .isEqualTo(220);

        verify(fallbackService, never()).liveAnimeInfo(any());
    }

    @Test
    @DisplayName(
            "GET /anime/{slug} cache-miss: live fallback wins, response carries fwd=miss;"
                    + " fallback")
    void animeInfoCacheMissFallback() {
        when(readService.findAnimeInfo("naruto")).thenReturn(Optional.empty());
        JutsuAnimeInfo info =
                new JutsuAnimeInfo(
                        "naruto",
                        "Наруто",
                        "Naruto",
                        "synopsis",
                        Optional.of(JutsuYear.BEFORE_2000),
                        List.of(),
                        Optional.empty(),
                        Set.of(JutsuGenre.ACTION),
                        Set.of(),
                        "thumb",
                        List.of());
        when(fallbackService.liveAnimeInfo("naruto")).thenReturn(Mono.just(info));

        client.get()
                .uri("/api/v1/sources/jutsu/anime/naruto")
                .exchange()
                .expectStatus()
                .isOk()
                .expectHeader()
                .valueEquals("Cache-Status", "orinuno; fwd=miss; fallback")
                .expectBody()
                .jsonPath("$.slug")
                .isEqualTo("naruto")
                .jsonPath("$.year")
                .isEqualTo("before2000");

        verify(fallbackService).liveAnimeInfo("naruto");
    }

    @Test
    @DisplayName("GET /anime/{slug} cache-miss + breaker open: 503 with diagnostic header")
    void animeInfoCacheMissBreakerOpen() {
        when(readService.findAnimeInfo("naruto")).thenReturn(Optional.empty());
        when(fallbackService.liveAnimeInfo("naruto"))
                .thenReturn(
                        Mono.error(
                                new JutsuLiveFallbackService.BreakerOpenException(
                                        JutsuFallbackCircuitBreaker.State.HALF_OPEN)));

        client.get()
                .uri("/api/v1/sources/jutsu/anime/naruto")
                .exchange()
                .expectStatus()
                .isEqualTo(503)
                .expectHeader()
                .valueEquals("X-Orinuno-Fallback-Reason", "circuit-breaker-open");
    }

    // -------------------------------------------------------------------------
    // /search — bypasses cache by design
    // -------------------------------------------------------------------------

    @Test
    @DisplayName(
            "GET /search bypasses cache: read service NOT consulted, Cache-Status=fwd=bypass,"
                    + " live SDK invoked")
    void searchAlwaysHitsLive() {
        JutsuCatalogPage page = new JutsuCatalogPage(List.of(), 1, false);
        when(jutsuClient.searchByTitle("история", 1)).thenReturn(Mono.just(page));

        client.get()
                .uri(b -> b.path("/api/v1/sources/jutsu/search").queryParam("q", "история").build())
                .exchange()
                .expectStatus()
                .isOk()
                .expectHeader()
                .valueEquals("Cache-Status", "orinuno; fwd=bypass");

        verify(jutsuClient).searchByTitle("история", 1);
        verify(readService, never()).findCatalogPage(any());
        verify(fallbackService, never()).liveBrowseCatalog(any());
    }

    @Test
    @DisplayName("GET /search composes filter + query")
    void searchByTitleWithFilter() {
        JutsuCatalogPage page = new JutsuCatalogPage(List.of(), 3, true);
        when(jutsuClient.searchByTitle(any(JutsuCatalogFilter.class), eq("история"), eq(3)))
                .thenReturn(Mono.just(page));

        client.get()
                .uri(
                        b ->
                                b.path("/api/v1/sources/jutsu/search")
                                        .queryParam("q", "история")
                                        .queryParam("page", "3")
                                        .queryParam("genres", "COMEDY")
                                        .build())
                .exchange()
                .expectStatus()
                .isOk();

        verify(jutsuClient).searchByTitle(any(JutsuCatalogFilter.class), eq("история"), eq(3));
    }

    // -------------------------------------------------------------------------
    // Pass-through endpoints (unchanged by Step 3.C)
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("GET /episode returns the typed metadata DTO with kind=episode")
    void getEpisodeMeta() {
        JutsuEpisodeMeta meta =
                new JutsuEpisodeMeta(
                        "onepuunchman",
                        1,
                        1,
                        "Ванпанчмен 1 сезон 1 серия",
                        "Смотреть",
                        "https://jut.su/onepuunchman/season-1/episode-1.html",
                        "thumb.jpg",
                        null,
                        "/onepuunchman/season-1/episode-2.html",
                        "/onepuunchman/",
                        true);
        when(jutsuClient.getEpisodeMeta("https://jut.su/onepuunchman/season-1/episode-1.html"))
                .thenReturn(Mono.<JutsuPageMeta>just(meta));

        client.get()
                .uri(
                        b ->
                                b.path("/api/v1/sources/jutsu/episode")
                                        .queryParam(
                                                "url",
                                                "https://jut.su/onepuunchman/season-1/episode-1.html")
                                        .build())
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.kind")
                .isEqualTo("episode")
                .jsonPath("$.slug")
                .isEqualTo("onepuunchman")
                .jsonPath("$.season")
                .isEqualTo(1)
                .jsonPath("$.episode")
                .isEqualTo(1)
                .jsonPath("$.premiumGated")
                .isEqualTo(true)
                .jsonPath("$.prevEpisodeUrl")
                .doesNotExist();
    }

    @Test
    @DisplayName("GET /episode returns kind=film for full-length-movie URLs")
    void getEpisodeMetaForFilm() {
        JutsuFilmMeta film =
                new JutsuFilmMeta(
                        "life-no-game",
                        1,
                        "Смотреть 1 фильм Нет игры - нет жизни",
                        "Смотреть Нет игры - нет жизни 1 фильм на Jut.su",
                        "https://jut.su/life-no-game/film-1.html",
                        "thumb.jpg",
                        null,
                        null,
                        "/life-no-game/",
                        true);
        when(jutsuClient.getEpisodeMeta("https://jut.su/life-no-game/film-1.html"))
                .thenReturn(Mono.<JutsuPageMeta>just(film));

        client.get()
                .uri(
                        b ->
                                b.path("/api/v1/sources/jutsu/episode")
                                        .queryParam(
                                                "url", "https://jut.su/life-no-game/film-1.html")
                                        .build())
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.kind")
                .isEqualTo("film")
                .jsonPath("$.slug")
                .isEqualTo("life-no-game")
                .jsonPath("$.filmIndex")
                .isEqualTo(1)
                .jsonPath("$.allEpisodesUrl")
                .isEqualTo("/life-no-game/")
                .jsonPath("$.premiumGated")
                .isEqualTo(true)
                .jsonPath("$.season")
                .doesNotExist()
                .jsonPath("$.episode")
                .doesNotExist()
                .jsonPath("$.prevFilmUrl")
                .doesNotExist();
    }

    @Test
    @DisplayName("GET /notice without cursor calls getLatestNoticeFeed")
    void getNoticeFeedLatest() {
        JutsuNoticeFeed feed = new JutsuNoticeFeed(100, List.of());
        when(jutsuClient.getLatestNoticeFeed()).thenReturn(Mono.just(feed));

        client.get()
                .uri("/api/v1/sources/jutsu/notice")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.requestedCursor")
                .isEqualTo(100)
                .jsonPath("$.hasEntries")
                .isEqualTo(false);

        verify(jutsuClient).getLatestNoticeFeed();
    }

    @Test
    @DisplayName("GET /notice?cursor=X calls getNoticeFeed(X)")
    void getNoticeFeedExplicitCursor() {
        JutsuNoticeFeed feed =
                new JutsuNoticeFeed(
                        50,
                        List.of(
                                new JutsuNoticeEntry(
                                        "x",
                                        1,
                                        2,
                                        "X: 2 серия",
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
                .isEqualTo(50)
                .jsonPath("$.entries.length()")
                .isEqualTo(1)
                .jsonPath("$.nextCursor")
                .isEqualTo(49);
    }

    @Test
    @DisplayName("GET /notice/stream pipes streamNoticeEntries() as NDJSON")
    void streamNoticeEntries() {
        JutsuNoticeEntry e1 =
                new JutsuNoticeEntry(
                        "x", 1, 1, "X: 1", "https://jut.su/x/episode-1.html", null, "сегодня");
        JutsuNoticeEntry e2 =
                new JutsuNoticeEntry(
                        "y",
                        2,
                        3,
                        "Y: 3",
                        "https://jut.su/y/season-2/episode-3.html",
                        null,
                        "вчера");
        when(jutsuClient.streamNoticeEntries(anyInt(), anyInt())).thenReturn(Flux.just(e1, e2));

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
        JutsuDriftDetector detector = new JutsuDriftDetector();
        JutsuDriftSnapshot snapshot = detector.snapshot();
        when(jutsuClient.getDriftSnapshot()).thenReturn(snapshot);

        client.get()
                .uri("/api/v1/sources/jutsu/drift")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.health")
                .isEqualTo("HEALTHY")
                .jsonPath("$.lifetimeEvents")
                .isEqualTo(0)
                .jsonPath("$.recentEvents")
                .isArray();
    }
}
