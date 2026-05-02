package com.orinuno.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.orinuno.client.embed.KodikEmbedException;
import com.orinuno.client.embed.KodikEmbedHttpClient;
import com.orinuno.client.embed.KodikIdType;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

@ExtendWith(MockitoExtension.class)
class KodikEmbedServiceTest {

    @Mock private KodikEmbedHttpClient httpClient;

    @InjectMocks private KodikEmbedService service;

    @Test
    @DisplayName("Happy path: protocol-relative link is normalised to https and serial detected")
    void happyPathSerial() {
        when(httpClient.getPlayerRaw(KodikIdType.SHIKIMORI, "20"))
                .thenReturn(
                        Mono.just(
                                map(
                                        "found",
                                        true,
                                        "link",
                                        "//kodikplayer.com/serial/73959/abc/720p")));

        StepVerifier.create(service.resolve(KodikIdType.SHIKIMORI, "20"))
                .assertNext(
                        dto -> {
                            assertThat(dto.idType()).isEqualTo(KodikIdType.SHIKIMORI);
                            assertThat(dto.requestedId()).isEqualTo("20");
                            assertThat(dto.normalizedId()).isEqualTo("20");
                            assertThat(dto.embedLink())
                                    .isEqualTo("https://kodikplayer.com/serial/73959/abc/720p");
                            assertThat(dto.mediaType()).isEqualTo("serial");
                        })
                .verifyComplete();
    }

    @Test
    @DisplayName("Imdb id without tt prefix is normalised before forwarding to client")
    void imdbNormalisedBeforeClientCall() {
        when(httpClient.getPlayerRaw(eq(KodikIdType.IMDB), eq("tt0903747")))
                .thenReturn(
                        Mono.just(
                                map(
                                        "found",
                                        true,
                                        "link",
                                        "https://kodikplayer.com/video/1/abc/720p")));

        StepVerifier.create(service.resolve(KodikIdType.IMDB, "0903747"))
                .assertNext(
                        dto -> {
                            assertThat(dto.requestedId()).isEqualTo("0903747");
                            assertThat(dto.normalizedId()).isEqualTo("tt0903747");
                            assertThat(dto.mediaType()).isEqualTo("video");
                        })
                .verifyComplete();

        verify(httpClient).getPlayerRaw(KodikIdType.IMDB, "tt0903747");
    }

    @Test
    @DisplayName("found=false maps to NotFoundException")
    void foundFalseMapsToNotFound() {
        when(httpClient.getPlayerRaw(any(), any())).thenReturn(Mono.just(map("found", false)));

        StepVerifier.create(service.resolve(KodikIdType.KINOPOISK, "12345"))
                .expectError(KodikEmbedException.NotFoundException.class)
                .verify();
    }

    @Test
    @DisplayName("Non-token error field maps to UpstreamException")
    void errorMapsToUpstreamException() {
        when(httpClient.getPlayerRaw(any(), any()))
                .thenReturn(Mono.just(map("error", "Service temporarily unavailable")));

        StepVerifier.create(service.resolve(KodikIdType.SHIKIMORI, "20"))
                .expectErrorSatisfies(
                        ex -> {
                            assertThat(ex)
                                    .isInstanceOf(KodikEmbedException.UpstreamException.class)
                                    .hasMessageContaining("Service temporarily unavailable");
                        })
                .verify();
    }

    @Test
    @DisplayName("found=true but missing link maps to MalformedResponseException")
    void missingLinkMapsToMalformed() {
        when(httpClient.getPlayerRaw(any(), any())).thenReturn(Mono.just(map("found", true)));

        StepVerifier.create(service.resolve(KodikIdType.SHIKIMORI, "20"))
                .expectError(KodikEmbedException.MalformedResponseException.class)
                .verify();
    }

    @Test
    @DisplayName("Blank id rejected before any HTTP call")
    void blankIdShortCircuits() {
        StepVerifier.create(service.resolve(KodikIdType.SHIKIMORI, "  "))
                .expectError(IllegalArgumentException.class)
                .verify();

        verify(httpClient, never()).getPlayerRaw(any(), any());
    }

    @ParameterizedTest(name = "normalizeEmbedUrl({0}) → {1}")
    @CsvSource({
        "//kodikplayer.com/serial/1/abc/720p,https://kodikplayer.com/serial/1/abc/720p",
        "http://kodikplayer.com/serial/1/abc/720p,https://kodikplayer.com/serial/1/abc/720p",
        "https://kodikplayer.com/video/1/abc/720p,https://kodikplayer.com/video/1/abc/720p",
        "kodikplayer.com/serial/1/abc/720p,https://kodikplayer.com/serial/1/abc/720p"
    })
    void normaliseEmbedUrl(String raw, String expected) {
        assertThat(KodikEmbedService.normalizeEmbedUrl(raw)).isEqualTo(expected);
    }

    @ParameterizedTest(name = "detectMediaType({0}) → {1}")
    @CsvSource({
        "https://kodikplayer.com/serial/1/abc/720p,serial",
        "https://kodikplayer.com/video/1/abc/720p,video",
        "https://kodikplayer.com/movie/1/abc/720p,",
        "https://other.host/serial/1/abc/720p,",
        "https://kodikplayer.com/,"
    })
    void detectMediaType(String url, String expected) {
        assertThat(KodikEmbedService.detectMediaType(url)).isEqualTo(expected);
    }

    private static Map<String, Object> map(Object... pairs) {
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            m.put(pairs[i].toString(), pairs[i + 1]);
        }
        return m;
    }
}
