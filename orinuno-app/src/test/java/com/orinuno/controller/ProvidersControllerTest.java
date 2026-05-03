package com.orinuno.controller;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.orinuno.service.provider.ProviderDecodeResult;
import com.orinuno.service.provider.aniboom.AniboomDecoderService;
import com.orinuno.service.provider.jutsu.JutsuDecoderService;
import com.orinuno.service.provider.sibnet.SibnetDecoderService;
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

    @Mock private SibnetDecoderService sibnetDecoder;
    @Mock private AniboomDecoderService aniboomDecoder;
    @Mock private JutsuDecoderService jutsuDecoder;

    private WebTestClient client;

    @BeforeEach
    void setUp() {
        ProvidersController controller =
                new ProvidersController(sibnetDecoder, aniboomDecoder, jutsuDecoder);
        client = WebTestClient.bindToController(controller).build();
    }

    @Test
    @DisplayName("POST /api/v1/providers/decode routes SIBNET to the Sibnet decoder")
    void routesSibnet() {
        when(sibnetDecoder.decode(eq("https://video.sibnet.ru/shell.php?videoid=1")))
                .thenReturn(
                        Mono.just(
                                ProviderDecodeResult.success(
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

        verifyNoInteractions(aniboomDecoder, jutsuDecoder);
    }

    @Test
    @DisplayName("POST /api/v1/providers/decode routes ANIBOOM to the Aniboom decoder")
    void routesAniboom() {
        when(aniboomDecoder.decode(eq("https://aniboom.one/embed/abc")))
                .thenReturn(Mono.just(ProviderDecodeResult.failure("ANIBOOM_GEO_BLOCKED")));

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

        verify(sibnetDecoder, never()).decode(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    @DisplayName("POST /api/v1/providers/decode routes JUTSU to the JutSu decoder")
    void routesJutsu() {
        when(jutsuDecoder.decode(eq("https://jut.su/naruto/episode-1.html")))
                .thenReturn(
                        Mono.just(
                                ProviderDecodeResult.success(
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

        verifyNoInteractions(sibnetDecoder, aniboomDecoder, jutsuDecoder);
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
