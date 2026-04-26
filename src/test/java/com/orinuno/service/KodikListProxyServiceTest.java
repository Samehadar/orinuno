package com.orinuno.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.orinuno.client.KodikApiClient;
import com.orinuno.client.dto.KodikListRequest;
import com.orinuno.drift.DriftDetector;
import com.orinuno.drift.DriftSamplingProperties;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;

@ExtendWith(MockitoExtension.class)
class KodikListProxyServiceTest {

    @Mock private KodikApiClient kodikApiClient;
    private DriftDetector drift;
    private KodikListProxyService service;

    @BeforeEach
    void setUp() {
        drift = new DriftDetector(DriftSamplingProperties.defaults());
        service = new KodikListProxyService(kodikApiClient, drift);
    }

    @Test
    @DisplayName("maps raw payload to KodikListPageView")
    void mapsRawToView() {
        Map<String, Object> material = new HashMap<>();
        material.put("poster_url_original", "https://example.com/poster_orig.jpg");
        material.put("anime_status", "ongoing");
        Map<String, Object> item = new HashMap<>();
        item.put("id", "kodik-001");
        item.put("type", "anime-serial");
        item.put("title", "Naruto");
        item.put("year", 2002);
        item.put("kinopoisk_id", "111");
        item.put("imdb_id", "tt000111");
        item.put("last_season", 1);
        item.put("last_episode", 220);
        item.put("episodes_count", 220);
        item.put("material_data", material);
        item.put("updated_at", "2026-04-26T00:00:00Z");
        Map<String, Object> raw = new HashMap<>();
        raw.put("results", List.of(item));
        raw.put("total", 1);
        raw.put("next_page", "https://kodik-api.com/list?next=...");

        when(kodikApiClient.listRaw(any(KodikListRequest.class))).thenReturn(Mono.just(raw));

        KodikListProxyService.ProxyPage page = service.list(new KodikListRequest()).block();

        assertThat(page).isNotNull();
        assertThat(page.schemaDriftObserved()).isFalse();
        assertThat(page.page().total()).isEqualTo(1);
        assertThat(page.page().nextPage()).startsWith("https://kodik-api.com/list?next=");
        assertThat(page.page().results()).hasSize(1);
        assertThat(page.page().results().get(0).id()).isEqualTo("kodik-001");
        assertThat(page.page().results().get(0).posterUrl())
                .isEqualTo("https://example.com/poster_orig.jpg");
        assertThat(page.page().results().get(0).animeStatus()).isEqualTo("ongoing");
        assertThat(page.page().results().get(0).lastEpisode()).isEqualTo(220);
    }

    @Test
    @DisplayName("falls back to poster_url when poster_url_original missing")
    void fallsBackToPosterUrl() {
        Map<String, Object> material = Map.of("poster_url", "https://example.com/poster.jpg");
        Map<String, Object> item = new HashMap<>();
        item.put("id", "kodik-002");
        item.put("material_data", material);
        Map<String, Object> raw = Map.of("results", List.of(item));
        when(kodikApiClient.listRaw(any(KodikListRequest.class))).thenReturn(Mono.just(raw));

        KodikListProxyService.ProxyPage page = service.list(new KodikListRequest()).block();

        assertThat(page).isNotNull();
        assertThat(page.page().results().get(0).posterUrl())
                .isEqualTo("https://example.com/poster.jpg");
    }

    @Test
    @DisplayName("schemaDriftObserved=true when DriftDetector counter increments during call")
    void detectsDriftDuringCall() {
        when(kodikApiClient.listRaw(any(KodikListRequest.class)))
                .thenReturn(
                        Mono.fromSupplier(
                                () -> {
                                    drift.getTotalDriftsDetected().incrementAndGet();
                                    return Map.of("results", List.<Object>of());
                                }));

        KodikListProxyService.ProxyPage page = service.list(new KodikListRequest()).block();

        assertThat(page).isNotNull();
        assertThat(page.schemaDriftObserved()).isTrue();
    }
}
