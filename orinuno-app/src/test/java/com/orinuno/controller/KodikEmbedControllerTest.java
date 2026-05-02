package com.orinuno.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.orinuno.client.embed.KodikEmbedException;
import com.orinuno.client.embed.KodikIdType;
import com.orinuno.model.dto.EmbedLinkDto;
import com.orinuno.service.KodikEmbedService;
import com.orinuno.token.KodikTokenException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

@ExtendWith(MockitoExtension.class)
class KodikEmbedControllerTest {

    @Mock private KodikEmbedService service;

    private WebTestClient client;

    @BeforeEach
    void setUp() {
        client = WebTestClient.bindToController(new KodikEmbedController(service)).build();
    }

    @Test
    @DisplayName("GET /embed/{type}/{id} returns 200 + EmbedLinkDto on happy path")
    void resolvesEmbed() {
        when(service.resolve(eq(KodikIdType.SHIKIMORI), eq("20")))
                .thenReturn(
                        Mono.just(
                                new EmbedLinkDto(
                                        KodikIdType.SHIKIMORI,
                                        "20",
                                        "20",
                                        "https://kodikplayer.com/serial/73959/abc/720p",
                                        "serial")));

        client.get()
                .uri("/api/v1/embed/shikimori/20")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.idType")
                .isEqualTo("shikimori")
                .jsonPath("$.normalizedId")
                .isEqualTo("20")
                .jsonPath("$.embedLink")
                .isEqualTo("https://kodikplayer.com/serial/73959/abc/720p")
                .jsonPath("$.mediaType")
                .isEqualTo("serial");
    }

    @Test
    @DisplayName("Unknown idType returns 400 without invoking service")
    void unknownIdTypeReturns400() {
        client.get()
                .uri("/api/v1/embed/anilist/20")
                .exchange()
                .expectStatus()
                .isBadRequest()
                .expectBody()
                .jsonPath("$.error")
                .value(msg -> assertThat(msg.toString()).contains("Unknown idType"));

        verify(service, never()).resolve(any(), any());
    }

    @Test
    @DisplayName("Path mapper accepts kebab-case slugs (worldart-animation)")
    void kebabCaseAccepted() {
        when(service.resolve(eq(KodikIdType.WORLDART_ANIMATION), eq("7659")))
                .thenReturn(
                        Mono.just(
                                new EmbedLinkDto(
                                        KodikIdType.WORLDART_ANIMATION,
                                        "7659",
                                        "7659",
                                        "https://kodikplayer.com/serial/1/abc/720p",
                                        "serial")));

        client.get()
                .uri("/api/v1/embed/worldart-animation/7659")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.idType")
                .isEqualTo("worldart_animation");
    }

    @Test
    @DisplayName("NotFoundException from service maps to HTTP 404")
    void notFoundReturns404() {
        when(service.resolve(any(), any()))
                .thenReturn(
                        Mono.error(
                                new KodikEmbedException.NotFoundException("nothing for this id")));

        client.get()
                .uri("/api/v1/embed/shikimori/99999999")
                .exchange()
                .expectStatus()
                .isNotFound()
                .expectBody()
                .jsonPath("$.error")
                .isEqualTo("nothing for this id");
    }

    @Test
    @DisplayName("UpstreamException maps to HTTP 502 Bad Gateway")
    void upstreamErrorReturns502() {
        when(service.resolve(any(), any()))
                .thenReturn(
                        Mono.error(
                                new KodikEmbedException.UpstreamException(
                                        "Kodik /get-player error: rate-limit")));

        client.get()
                .uri("/api/v1/embed/shikimori/20")
                .exchange()
                .expectStatus()
                .isEqualTo(org.springframework.http.HttpStatus.BAD_GATEWAY)
                .expectBody()
                .jsonPath("$.error")
                .value(msg -> assertThat(msg.toString()).contains("rate-limit"));
    }

    @Test
    @DisplayName("MalformedResponseException maps to HTTP 502 Bad Gateway")
    void malformedReturns502() {
        when(service.resolve(any(), any()))
                .thenReturn(
                        Mono.error(
                                new KodikEmbedException.MalformedResponseException(
                                        "no link field")));

        client.get()
                .uri("/api/v1/embed/shikimori/20")
                .exchange()
                .expectStatus()
                .isEqualTo(org.springframework.http.HttpStatus.BAD_GATEWAY);
    }

    @Test
    @DisplayName("NoWorkingTokenException maps to HTTP 503")
    void noTokenReturns503() {
        when(service.resolve(any(), any()))
                .thenReturn(
                        Mono.error(
                                new KodikTokenException.NoWorkingTokenException("registry empty")));

        client.get()
                .uri("/api/v1/embed/shikimori/20")
                .exchange()
                .expectStatus()
                .isEqualTo(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test
    @DisplayName("TokenRejectedException maps to HTTP 503")
    void tokenRejectedReturns503() {
        when(service.resolve(any(), any()))
                .thenReturn(
                        Mono.error(
                                new KodikTokenException.TokenRejectedException("all tokens dead")));

        client.get()
                .uri("/api/v1/embed/shikimori/20")
                .exchange()
                .expectStatus()
                .isEqualTo(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test
    @DisplayName("IllegalArgumentException from service (blank id reaches resolve) maps to 400")
    void illegalArgumentReturns400() {
        when(service.resolve(any(), any()))
                .thenReturn(Mono.error(new IllegalArgumentException("id must not be blank")));

        client.get().uri("/api/v1/embed/shikimori/x").exchange().expectStatus().isBadRequest();
    }
}
