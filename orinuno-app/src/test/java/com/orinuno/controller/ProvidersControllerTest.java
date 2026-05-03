package com.orinuno.controller;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.orinuno.aniboom.AniboomClient;
import com.orinuno.aniboom.AniboomDecodeResult;
import com.orinuno.aniboom.AniboomErrorCodes;
import com.orinuno.jutsu.JutsuClient;
import com.orinuno.jutsu.JutsuDecodeResult;
import com.orinuno.sibnet.SibnetClient;
import com.orinuno.sibnet.SibnetDecodeResult;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

@ExtendWith(MockitoExtension.class)
class ProvidersControllerTest {

    @Mock private SibnetClient sibnetClient;
    @Mock private AniboomClient aniboomClient;
    @Mock private JutsuClient jutsuClient;

    private WebTestClient client;

    @BeforeEach
    void setUp() {
        ProvidersController controller =
                new ProvidersController(sibnetClient, aniboomClient, jutsuClient);
        client = WebTestClient.bindToController(controller).build();
    }

    @Test
    @DisplayName("POST /api/v1/providers/decode routes SIBNET to SibnetClient")
    void routesSibnet() {
        when(sibnetClient.decode(eq("https://video.sibnet.ru/shell.php?videoid=1")))
                .thenReturn(
                        Mono.just(
                                SibnetDecodeResult.success(
                                        Map.of("720", "https://cdn/m.mp4"), "video/mp4")));

        client.post()
                .uri("/api/v1/providers/decode")
                .bodyValue(
                        Map.of(
                                "provider",
                                "SIBNET",
                                "url",
                                "https://video.sibnet.ru/shell.php?videoid=1"))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.success")
                .isEqualTo(true)
                .jsonPath("$.format")
                .isEqualTo("video/mp4")
                .jsonPath("$.qualities.720")
                .isEqualTo("https://cdn/m.mp4");

        verifyNoInteractions(aniboomClient, jutsuClient);
    }

    @Test
    @DisplayName("POST /api/v1/providers/decode routes ANIBOOM to AniboomClient")
    void routesAniboom() {
        when(aniboomClient.decode(eq("https://aniboom.one/embed/abc")))
                .thenReturn(
                        Mono.just(
                                AniboomDecodeResult.failure(
                                        AniboomErrorCodes.ANIBOOM_GEO_BLOCKED)));

        client.post()
                .uri("/api/v1/providers/decode")
                .bodyValue(Map.of("provider", "aniboom", "url", "https://aniboom.one/embed/abc"))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.success")
                .isEqualTo(false)
                .jsonPath("$.errorCode")
                .isEqualTo("ANIBOOM_GEO_BLOCKED");

        verify(sibnetClient, never()).decode(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    @DisplayName("POST /api/v1/providers/decode routes JUTSU to JutsuClient")
    void routesJutsu() {
        when(jutsuClient.decode(eq("https://jut.su/naruto/episode-1.html")))
                .thenReturn(
                        Mono.just(
                                JutsuDecodeResult.success(
                                        Map.of("720", "https://x/720.mp4"), "video/mp4")));

        client.post()
                .uri("/api/v1/providers/decode")
                .bodyValue(
                        Map.of("provider", "JUTSU", "url", "https://jut.su/naruto/episode-1.html"))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.qualities.720")
                .isEqualTo("https://x/720.mp4");
    }

    @Test
    @DisplayName("POST /api/v1/providers/decode rejects unsupported provider with 400")
    void rejectsUnknownProvider() {
        client.post()
                .uri("/api/v1/providers/decode")
                .bodyValue(Map.of("provider", "VIMEO", "url", "https://vimeo.com/1"))
                .exchange()
                .expectStatus()
                .isBadRequest()
                .expectBody()
                .jsonPath("$.success")
                .isEqualTo(false)
                .jsonPath("$.errorCode")
                .isEqualTo("UNSUPPORTED_PROVIDER:VIMEO");

        verifyNoInteractions(sibnetClient, aniboomClient, jutsuClient);
    }

    @Test
    @DisplayName("POST /api/v1/providers/decode validates blank fields with 400")
    void rejectsBlankFields() {
        client.post()
                .uri("/api/v1/providers/decode")
                .bodyValue(Map.of("provider", "", "url", ""))
                .exchange()
                .expectStatus()
                .is4xxClientError();
    }
}
