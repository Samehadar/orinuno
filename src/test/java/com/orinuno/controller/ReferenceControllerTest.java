package com.orinuno.controller;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.orinuno.client.KodikApiClient;
import com.orinuno.client.dto.reference.KodikCountryDto;
import com.orinuno.client.dto.reference.KodikGenreDto;
import com.orinuno.client.dto.reference.KodikQualityDto;
import com.orinuno.client.dto.reference.KodikReferenceResponse;
import com.orinuno.client.dto.reference.KodikTranslationDto;
import com.orinuno.client.dto.reference.KodikYearDto;
import com.orinuno.service.ReferenceService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

@ExtendWith(MockitoExtension.class)
@DisplayName("ReferenceController — cache/fresh routing")
class ReferenceControllerTest {

    @Mock private ReferenceService referenceService;
    @Mock private KodikApiClient kodikApiClient;

    private WebTestClient webTestClient;

    @BeforeEach
    void setUp() {
        ReferenceController controller = new ReferenceController(referenceService, kodikApiClient);
        webTestClient = WebTestClient.bindToController(controller).build();
    }

    @Test
    @DisplayName("default call goes through ReferenceService (cache path)")
    void translationsUseCacheByDefault() {
        KodikReferenceResponse<KodikTranslationDto> response =
                KodikReferenceResponse.<KodikTranslationDto>builder()
                        .time("1ms")
                        .total(1)
                        .results(List.of(new KodikTranslationDto(610, "AniLibria", 42)))
                        .build();
        when(referenceService.translations()).thenReturn(response);

        webTestClient
                .get()
                .uri("/api/v1/reference/translations")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.total")
                .isEqualTo(1)
                .jsonPath("$.results[0].id")
                .isEqualTo(610)
                .jsonPath("$.results[0].title")
                .isEqualTo("AniLibria");

        verify(referenceService).translations();
        verify(kodikApiClient, never()).translations();
    }

    @Test
    @DisplayName("fresh=true bypasses cache and calls KodikApiClient directly")
    void translationsFreshBypassesCache() {
        KodikReferenceResponse<KodikTranslationDto> live =
                KodikReferenceResponse.<KodikTranslationDto>builder()
                        .time("5ms")
                        .total(0)
                        .results(List.of())
                        .build();
        when(kodikApiClient.translations()).thenReturn(Mono.just(live));

        webTestClient
                .get()
                .uri("/api/v1/reference/translations?fresh=true")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.time")
                .isEqualTo("5ms");

        verify(kodikApiClient).translations();
        verify(referenceService, never()).translations();
    }

    @Test
    @DisplayName("/genres returns typed payload")
    void genresReturnsTypedPayload() {
        when(referenceService.genres())
                .thenReturn(
                        KodikReferenceResponse.<KodikGenreDto>builder()
                                .time("1ms")
                                .total(1)
                                .results(List.of(new KodikGenreDto("anime", 10)))
                                .build());

        webTestClient
                .get()
                .uri("/api/v1/reference/genres")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.results[0].title")
                .isEqualTo("anime");
    }

    @Test
    @DisplayName("/countries returns typed payload")
    void countriesReturnsTypedPayload() {
        when(referenceService.countries())
                .thenReturn(
                        KodikReferenceResponse.<KodikCountryDto>builder()
                                .time("1ms")
                                .total(1)
                                .results(List.of(new KodikCountryDto("Япония", 3)))
                                .build());

        webTestClient
                .get()
                .uri("/api/v1/reference/countries")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.results[0].title")
                .isEqualTo("Япония");
    }

    @Test
    @DisplayName("/years returns typed payload with year key")
    void yearsReturnsTypedPayload() {
        when(referenceService.years())
                .thenReturn(
                        KodikReferenceResponse.<KodikYearDto>builder()
                                .time("1ms")
                                .total(1)
                                .results(List.of(new KodikYearDto(2025, 7)))
                                .build());

        webTestClient
                .get()
                .uri("/api/v1/reference/years")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.results[0].year")
                .isEqualTo(2025);
    }

    @Test
    @DisplayName("/qualities returns typed payload")
    void qualitiesReturnsTypedPayload() {
        when(referenceService.qualities())
                .thenReturn(
                        KodikReferenceResponse.<KodikQualityDto>builder()
                                .time("1ms")
                                .total(1)
                                .results(List.of(new KodikQualityDto("WEB-DLRip", 5)))
                                .build());

        webTestClient
                .get()
                .uri("/api/v1/reference/qualities")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.results[0].title")
                .isEqualTo("WEB-DLRip");
    }
}
