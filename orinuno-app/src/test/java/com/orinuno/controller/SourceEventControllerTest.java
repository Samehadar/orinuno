package com.orinuno.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.orinuno.contract.source.ContentKindHint;
import com.orinuno.contract.source.ExternalIds;
import com.orinuno.contract.source.Provenance;
import com.orinuno.contract.source.SourceCatalogEvent;
import com.orinuno.contract.source.SourceContentInfo;
import com.orinuno.contract.source.SourceEpisodeVariant;
import com.orinuno.contract.source.SourceIdentifier;
import com.orinuno.service.ExportDataService;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.reactive.server.WebTestClient;

@ExtendWith(MockitoExtension.class)
class SourceEventControllerTest {

    private WebTestClient webTestClient;

    @Mock private ExportDataService exportDataService;

    @BeforeEach
    void setUp() {
        SourceEventController controller = new SourceEventController(exportDataService);
        webTestClient = WebTestClient.bindToController(controller).build();
    }

    @Test
    void emptyResultIsSerialisedAsEmptyArray() {
        when(exportDataService.findReadyForExportAsEvents(any(), any(Integer.class)))
                .thenReturn(List.of());

        webTestClient
                .get()
                .uri("/api/v1/source-events/ready")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .json("[]");
    }

    @Test
    void movieDiscoveredCarriesKindAndMediaUrlOnTheWire() {
        var event =
                new SourceCatalogEvent.MovieDiscovered(
                        SourceIdentifier.of("kodik", "100"),
                        SourceContentInfo.builder()
                                .titleRu("Title RU")
                                .titleEn("Title EN")
                                .year(2024)
                                .kindHint(ContentKindHint.MOVIE)
                                .externalIds(ExternalIds.builder().kinopoiskId("12345").build())
                                .build(),
                        new SourceEpisodeVariant(
                                SourceIdentifier.of("kodik", "11"),
                                "https://cdn/11.mp4",
                                "TR",
                                "HD",
                                null,
                                null),
                        Provenance.of(
                                "orinuno-app://kodik/raw100",
                                Instant.parse("2026-05-10T00:00:00Z")));
        when(exportDataService.findReadyForExportAsEvents(any(), any(Integer.class)))
                .thenReturn(List.of(event));

        webTestClient
                .get()
                .uri("/api/v1/source-events/ready")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$[0].kind")
                .isEqualTo("movie-discovered")
                .jsonPath("$[0].identifier.sourceType")
                .isEqualTo("kodik")
                .jsonPath("$[0].identifier.sourceId")
                .isEqualTo("100")
                .jsonPath("$[0].variant.mediaUrl")
                .isEqualTo("https://cdn/11.mp4")
                .jsonPath("$[0].info.externalIds.kinopoiskId")
                .isEqualTo("12345");
    }

    @Test
    void seriesDiscoveredCarriesSeasonsAndEpisodes() {
        var variant =
                new SourceEpisodeVariant(
                        SourceIdentifier.of("kodik", "50"),
                        "https://cdn/50.mp4",
                        "TR",
                        null,
                        null,
                        null);
        var episode =
                new com.orinuno.contract.source.SourceEpisode(
                        null, null, null, null, null, null, 1, List.of(variant));
        var season =
                new com.orinuno.contract.source.SourceSeason(null, null, null, 1, List.of(episode));
        var event =
                new SourceCatalogEvent.SeriesDiscovered(
                        SourceIdentifier.of("kodik", "100"),
                        SourceContentInfo.builder()
                                .titleRu("Series Title")
                                .kindHint(ContentKindHint.ANIME)
                                .externalIds(ExternalIds.empty())
                                .build(),
                        List.of(season),
                        Provenance.of(
                                "orinuno-app://kodik/raw100",
                                Instant.parse("2026-05-10T00:00:00Z")));
        when(exportDataService.findReadyForExportAsEvents(any(), any(Integer.class)))
                .thenReturn(List.of(event));

        webTestClient
                .get()
                .uri("/api/v1/source-events/ready?limit=10")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$[0].kind")
                .isEqualTo("series-discovered")
                .jsonPath("$[0].seasons[0].order")
                .isEqualTo(1)
                .jsonPath("$[0].seasons[0].episodes[0].variants[0].mediaUrl")
                .isEqualTo("https://cdn/50.mp4");
    }

    @Test
    void updatedSinceParameterIsPassedToService() {
        when(exportDataService.findReadyForExportAsEvents(any(), any(Integer.class)))
                .thenReturn(List.of());

        webTestClient
                .get()
                .uri("/api/v1/source-events/ready?updatedSince=2026-05-01T00:00:00")
                .exchange()
                .expectStatus()
                .isOk();

        ArgumentCaptor<LocalDateTime> captor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(exportDataService).findReadyForExportAsEvents(captor.capture(), eq(20));
        org.assertj.core.api.Assertions.assertThat(captor.getValue())
                .isEqualTo(LocalDateTime.parse("2026-05-01T00:00:00"));
    }

    @Test
    void limitIsClampedToMax200AndDefaultsTo20() {
        when(exportDataService.findReadyForExportAsEvents(any(), any(Integer.class)))
                .thenReturn(List.of());

        webTestClient
                .get()
                .uri("/api/v1/source-events/ready?limit=99999")
                .exchange()
                .expectStatus()
                .isOk();
        verify(exportDataService).findReadyForExportAsEvents(any(), eq(200));

        webTestClient
                .get()
                .uri("/api/v1/source-events/ready?limit=0")
                .exchange()
                .expectStatus()
                .isOk();
        verify(exportDataService).findReadyForExportAsEvents(any(), eq(20));
    }
}
