package com.orinuno.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.orinuno.configuration.OrinunoProperties;
import com.orinuno.service.KodikVideoDecoderService;
import com.orinuno.service.provider.ProviderDecodeResult;
import com.orinuno.service.provider.aniboom.AniboomDecoderService;
import com.orinuno.service.provider.jutsu.JutsuDecoderService;
import com.orinuno.service.provider.sibnet.SibnetDecoderService;
import java.util.List;
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
class SourcesControllerTest {

    @Mock private KodikVideoDecoderService kodikDecoder;
    @Mock private SibnetDecoderService sibnetDecoder;
    @Mock private AniboomDecoderService aniboomDecoder;
    @Mock private JutsuDecoderService jutsuDecoder;

    private OrinunoProperties properties;
    private WebTestClient client;

    @BeforeEach
    void setUp() {
        properties = new OrinunoProperties();
        SourcesController controller =
                new SourcesController(
                        kodikDecoder, sibnetDecoder, aniboomDecoder, jutsuDecoder, properties);
        client = WebTestClient.bindToController(controller).build();
    }

    @Test
    @DisplayName("GET /api/v1/sources returns capabilities for all four providers")
    void capabilitiesListsAllProviders() {
        client.get()
                .uri("/api/v1/sources")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.count")
                .isEqualTo(4)
                .jsonPath("$.providers[?(@.id=='kodik')].operations")
                .exists()
                .jsonPath("$.providers[?(@.id=='jutsu')].credentialsRequired")
                .isEqualTo(true)
                .jsonPath("$.providers[?(@.id=='jutsu')].credentialsConfigured")
                .isEqualTo(false)
                .jsonPath("$.providers[?(@.id=='sibnet')].credentialsRequired")
                .isEqualTo(false)
                .jsonPath("$.providers[?(@.id=='aniboom')].credentialsRequired")
                .isEqualTo(false);
    }

    @Test
    @DisplayName("GET /api/v1/sources reflects JutSu credentials when they are configured")
    void capabilitiesReflectsJutsuCredentialsWhenConfigured() {
        properties.getProviders().getJutsu().setUsername("amateurdevideo");
        properties.getProviders().getJutsu().setPassword("secret");

        client.get()
                .uri("/api/v1/sources")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.providers[?(@.id=='jutsu')].credentialsConfigured")
                .isEqualTo(true);
    }

    @Test
    @DisplayName("POST /api/v1/sources/sibnet/decode dispatches to SibnetDecoderService")
    void perSourceSibnetDispatch() {
        when(sibnetDecoder.decode(eq("https://video.sibnet.ru/shell.php?videoid=1")))
                .thenReturn(
                        Mono.just(
                                ProviderDecodeResult.success(
                                        Map.of("720", "https://cdn/m.mp4"), "video/mp4")));

        client.post()
                .uri("/api/v1/sources/sibnet/decode")
                .bodyValue(Map.of("url", "https://video.sibnet.ru/shell.php?videoid=1"))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.success")
                .isEqualTo(true)
                .jsonPath("$.qualities.720")
                .isEqualTo("https://cdn/m.mp4");

        verifyNoInteractions(kodikDecoder, aniboomDecoder, jutsuDecoder);
    }

    @Test
    @DisplayName("POST /api/v1/sources/aniboom/decode dispatches to AniboomDecoderService")
    void perSourceAniboomDispatch() {
        when(aniboomDecoder.decode(eq("https://aniboom.one/embed/abc")))
                .thenReturn(Mono.just(ProviderDecodeResult.failure("ANIBOOM_GEO_BLOCKED")));

        client.post()
                .uri("/api/v1/sources/aniboom/decode")
                .bodyValue(Map.of("url", "https://aniboom.one/embed/abc"))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.success")
                .isEqualTo(false)
                .jsonPath("$.errorCode")
                .isEqualTo("ANIBOOM_GEO_BLOCKED");
    }

    @Test
    @DisplayName("POST /api/v1/sources/jutsu/decode dispatches to JutsuDecoderService")
    void perSourceJutsuDispatch() {
        when(jutsuDecoder.decode(eq("https://jut.su/naruto/episode-1.html")))
                .thenReturn(
                        Mono.just(
                                ProviderDecodeResult.success(
                                        Map.of("720", "https://x/720.mp4"), "video/mp4")));

        client.post()
                .uri("/api/v1/sources/jutsu/decode")
                .bodyValue(Map.of("url", "https://jut.su/naruto/episode-1.html"))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.qualities.720")
                .isEqualTo("https://x/720.mp4");
    }

    @Test
    @DisplayName(
            "POST /api/v1/sources/kodik/decode dispatches to KodikVideoDecoderService and labels"
                    + " format as application/x-mpegURL")
    void perSourceKodikDispatch() {
        when(kodikDecoder.decode(eq("https://kodik.info/serial/123/abc/720p")))
                .thenReturn(
                        Mono.just(
                                Map.of(
                                        "720",
                                        "https://hls.kodik/720/master.m3u8",
                                        "480",
                                        "https://hls.kodik/480/master.m3u8")));

        client.post()
                .uri("/api/v1/sources/kodik/decode")
                .bodyValue(Map.of("url", "https://kodik.info/serial/123/abc/720p"))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.success")
                .isEqualTo(true)
                .jsonPath("$.format")
                .isEqualTo("application/x-mpegURL")
                .jsonPath("$.qualities.720")
                .isEqualTo("https://hls.kodik/720/master.m3u8")
                .jsonPath("$.qualities.480")
                .isEqualTo("https://hls.kodik/480/master.m3u8");

        verify(sibnetDecoder, never()).decode(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    @DisplayName(
            "POST /api/v1/sources/kodik/decode wraps decoder failures as KODIK_DECODE_ERROR"
                    + " instead of leaking the exception")
    void perSourceKodikWrapsErrors() {
        when(kodikDecoder.decode(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(Mono.error(new RuntimeException("upstream timeout")));

        client.post()
                .uri("/api/v1/sources/kodik/decode")
                .bodyValue(Map.of("url", "https://kodik.info/x/y/z"))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.success")
                .isEqualTo(false)
                .jsonPath("$.errorCode")
                .isEqualTo("KODIK_DECODE_ERROR");
    }

    @Test
    @DisplayName(
            "POST /api/v1/sources/{provider}/decode rejects unsupported provider with 400 and a"
                    + " descriptive errorCode")
    void perSourceRejectsUnknownProvider() {
        client.post()
                .uri("/api/v1/sources/vimeo/decode")
                .bodyValue(Map.of("url", "https://vimeo.com/1"))
                .exchange()
                .expectStatus()
                .isBadRequest()
                .expectBody()
                .jsonPath("$.success")
                .isEqualTo(false)
                .jsonPath("$.errorCode")
                .isEqualTo("UNSUPPORTED_PROVIDER:VIMEO");

        verifyNoInteractions(kodikDecoder, sibnetDecoder, aniboomDecoder, jutsuDecoder);
    }

    @Test
    @DisplayName("POST /api/v1/sources/{provider}/decode validates blank url with 400")
    void perSourceValidatesBlankUrl() {
        client.post()
                .uri("/api/v1/sources/sibnet/decode")
                .bodyValue(Map.of("url", ""))
                .exchange()
                .expectStatus()
                .is4xxClientError();
    }

    @Test
    @DisplayName("Provider segment is case-insensitive (sibnet/SIBNET/SiBNeT all dispatch)")
    void perSourceProviderCaseInsensitive() {
        when(sibnetDecoder.decode(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(
                        Mono.just(ProviderDecodeResult.success(Map.of("720", "u"), "video/mp4")));
        for (String segment : List.of("sibnet", "SIBNET", "SiBNeT")) {
            client.post()
                    .uri("/api/v1/sources/" + segment + "/decode")
                    .bodyValue(Map.of("url", "https://video.sibnet.ru/shell.php?videoid=1"))
                    .exchange()
                    .expectStatus()
                    .isOk();
        }
        verify(sibnetDecoder, org.mockito.Mockito.times(3))
                .decode(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    @DisplayName("Capabilities response always includes notes for each provider")
    void capabilitiesIncludesHumanReadableNotes() {
        var result = client.get().uri("/api/v1/sources").exchange().expectBody().returnResult();
        assertThat(result.getResponseBody()).isNotNull();
        String body = new String(result.getResponseBody());
        assertThat(body).contains("\"notes\"");
        assertThat(body).contains("kodik");
        assertThat(body).contains("jutsu");
        assertThat(body).contains("sibnet");
        assertThat(body).contains("aniboom");
    }
}
