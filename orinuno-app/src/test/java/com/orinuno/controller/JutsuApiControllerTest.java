package com.orinuno.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.orinuno.jutsu.JutsuClient;
import com.orinuno.jutsu.catalog.JutsuCatalogEntry;
import com.orinuno.jutsu.catalog.JutsuCatalogPage;
import com.orinuno.jutsu.drift.JutsuDriftDetector;
import com.orinuno.jutsu.drift.JutsuDriftSnapshot;
import com.orinuno.jutsu.episode.JutsuEpisodeMeta;
import com.orinuno.jutsu.filter.JutsuCatalogFilter;
import com.orinuno.jutsu.filter.JutsuGenre;
import com.orinuno.jutsu.filter.JutsuType;
import com.orinuno.jutsu.filter.JutsuYear;
import com.orinuno.jutsu.info.JutsuAnimeInfo;
import com.orinuno.jutsu.notice.JutsuNoticeEntry;
import com.orinuno.jutsu.notice.JutsuNoticeFeed;
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

    private WebTestClient client;

    @BeforeEach
    void setUp() {
        JutsuApiController controller = new JutsuApiController(jutsuClient);
        client = WebTestClient.bindToController(controller).build();
    }

    @Test
    @DisplayName("GET /catalog forwards page + decoded filter to JutsuClient")
    void browseCatalogParsesEnumNamesIntoFilter() {
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
        when(jutsuClient.browseCatalog(any(JutsuCatalogFilter.class), eq(2)))
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
                .expectBody()
                .jsonPath("$.page")
                .isEqualTo(2)
                .jsonPath("$.entries.length()")
                .isEqualTo(1)
                .jsonPath("$.entries[0].slug")
                .isEqualTo("naruto")
                .jsonPath("$.entries[0].genres[0]")
                .isEqualTo("action")
                .jsonPath("$.hasMore")
                .isEqualTo(true);

        ArgumentCaptor<JutsuCatalogFilter> captor =
                ArgumentCaptor.forClass(JutsuCatalogFilter.class);
        verify(jutsuClient).browseCatalog(captor.capture(), eq(2));
        JutsuCatalogFilter f = captor.getValue();
        assertThat(f.genres()).containsExactly(JutsuGenre.ACTION);
        assertThat(f.types()).containsExactly(JutsuType.SHONEN);
        assertThat(f.years()).containsExactly(JutsuYear.Y_2024);
    }

    @Test
    @DisplayName("GET /catalog tolerates unknown enum names without 5xx")
    void browseCatalogIgnoresUnknownEnumValues() {
        JutsuCatalogPage page = new JutsuCatalogPage(List.of(), 1, false);
        when(jutsuClient.browseCatalog(any(JutsuCatalogFilter.class), eq(1)))
                .thenReturn(Mono.just(page));

        client.get()
                .uri(
                        b ->
                                b.path("/api/v1/sources/jutsu/catalog")
                                        .queryParam("genres", "NOT_A_GENRE,ACTION")
                                        .build())
                .exchange()
                .expectStatus()
                .isOk();

        ArgumentCaptor<JutsuCatalogFilter> captor =
                ArgumentCaptor.forClass(JutsuCatalogFilter.class);
        verify(jutsuClient).browseCatalog(captor.capture(), eq(1));
        // The unknown value is dropped, the recognised one survives.
        assertThat(captor.getValue().genres()).containsExactly(JutsuGenre.ACTION);
    }

    @Test
    @DisplayName("GET /search routes through searchByTitle when no filter is supplied")
    void searchByTitleNoFilter() {
        JutsuCatalogPage page = new JutsuCatalogPage(List.of(), 1, false);
        when(jutsuClient.searchByTitle("история", 1)).thenReturn(Mono.just(page));

        client.get()
                .uri(b -> b.path("/api/v1/sources/jutsu/search").queryParam("q", "история").build())
                .exchange()
                .expectStatus()
                .isOk();

        verify(jutsuClient).searchByTitle("история", 1);
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

    @Test
    @DisplayName("GET /anime/{slug} returns the typed info DTO")
    void getAnimeInfo() {
        JutsuAnimeInfo info =
                new JutsuAnimeInfo(
                        "onepuunchman",
                        "Ванпанчмен",
                        "One Punch Man",
                        "synopsis",
                        Optional.of(JutsuYear.Y_2015_2023),
                        Set.of(JutsuGenre.ACTION, JutsuGenre.COMEDY),
                        Set.of(JutsuType.SUPERPOWER),
                        "thumb.jpg",
                        List.of());
        when(jutsuClient.getAnimeInfo("onepuunchman")).thenReturn(Mono.just(info));

        client.get()
                .uri("/api/v1/sources/jutsu/anime/onepuunchman")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.slug")
                .isEqualTo("onepuunchman")
                .jsonPath("$.title")
                .isEqualTo("Ванпанчмен")
                .jsonPath("$.year")
                .isEqualTo("2015-2023")
                .jsonPath("$.genres.length()")
                .isEqualTo(2);
    }

    @Test
    @DisplayName("GET /episode returns the typed metadata DTO")
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
                .thenReturn(Mono.just(meta));

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
                .jsonPath("$.slug")
                .isEqualTo("onepuunchman")
                .jsonPath("$.premiumGated")
                .isEqualTo(true)
                .jsonPath("$.prevEpisodeUrl")
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
