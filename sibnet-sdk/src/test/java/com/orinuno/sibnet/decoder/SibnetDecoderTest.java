package com.orinuno.sibnet.decoder;

import static org.assertj.core.api.Assertions.assertThat;

import com.orinuno.sibnet.SibnetConfig;
import com.orinuno.sibnet.SibnetDecodeResult;
import com.orinuno.sibnet.SibnetErrorCodes;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class SibnetDecoderTest {

    private static final String DEFAULT_BASE = "https://video.sibnet.ru";

    @Test
    void extractsFromTypicalShellHtml() {
        String html =
                "<html><body><script>"
                        + "var player = videojs('video');\n"
                        + "player.src([{src: \"/v/123/master.mp4\", type: \"video/mp4\"}]);"
                        + "</script></body></html>";

        SibnetDecodeResult r =
                SibnetDecoder.extractFromHtml(
                        html, "https://video.sibnet.ru/shell.php?videoid=123", DEFAULT_BASE);

        assertThat(r.success()).isTrue();
        assertThat(r.qualities()).containsEntry("720", "https://video.sibnet.ru/v/123/master.mp4");
        assertThat(r.format()).isEqualTo("video/mp4");
    }

    @Test
    void extractsAbsoluteHttpsUrl() {
        String html =
                "player.src([{src:\"https://cdn.sibnet.ru/abs/master.mp4\", type:\"video/mp4\"}]);";
        SibnetDecodeResult r =
                SibnetDecoder.extractFromHtml(
                        html, "https://video.sibnet.ru/shell.php?videoid=1", DEFAULT_BASE);
        assertThat(r.qualities()).containsEntry("720", "https://cdn.sibnet.ru/abs/master.mp4");
    }

    @Test
    void extractsProtocolRelativeUrl() {
        String html = "player.src([{src:\"//cdn.sibnet.ru/p.mp4\", type:\"video/mp4\"}]);";
        SibnetDecodeResult r =
                SibnetDecoder.extractFromHtml(
                        html, "https://video.sibnet.ru/shell.php?videoid=1", DEFAULT_BASE);
        assertThat(r.qualities()).containsEntry("720", "https://cdn.sibnet.ru/p.mp4");
    }

    @Test
    void failsOnRegexMiss() {
        SibnetDecodeResult r =
                SibnetDecoder.extractFromHtml(
                        "<html>no player here</html>",
                        "https://video.sibnet.ru/shell.php?videoid=1",
                        DEFAULT_BASE);
        assertThat(r.success()).isFalse();
        assertThat(r.errorCode()).isEqualTo(SibnetErrorCodes.SIBNET_PLAYER_REGEX_BREAK);
    }

    @Test
    void failsOnEmptyHtml() {
        assertThat(SibnetDecoder.extractFromHtml("", "u", DEFAULT_BASE).errorCode())
                .isEqualTo(SibnetErrorCodes.SIBNET_PLAYER_REGEX_BREAK);
        assertThat(SibnetDecoder.extractFromHtml(null, "u", DEFAULT_BASE).errorCode())
                .isEqualTo(SibnetErrorCodes.SIBNET_PLAYER_REGEX_BREAK);
    }

    @Test
    void absolutizeShapes() {
        String shell = "https://video.sibnet.ru/shell.php?videoid=1";
        assertThat(SibnetDecoder.absolutize("/v/x.mp4", shell, DEFAULT_BASE))
                .contains("https://video.sibnet.ru/v/x.mp4");
        assertThat(SibnetDecoder.absolutize("//cdn/x.mp4", shell, DEFAULT_BASE))
                .contains("https://cdn/x.mp4");
        assertThat(SibnetDecoder.absolutize("https://full/x.mp4", shell, DEFAULT_BASE))
                .contains("https://full/x.mp4");
        assertThat(SibnetDecoder.absolutize("rel/x.mp4", shell, DEFAULT_BASE))
                .contains("https://video.sibnet.ru/rel/x.mp4");
        assertThat(SibnetDecoder.absolutize(null, shell, DEFAULT_BASE)).isEmpty();
    }

    @Test
    void absolutizeRespectsCustomBaseUrl() {
        String shell = "https://example.org/shell.php?videoid=1";
        assertThat(SibnetDecoder.absolutize("/v/x.mp4", shell, "https://example.org"))
                .contains("https://example.org/v/x.mp4");
        assertThat(SibnetDecoder.absolutize("rel/x.mp4", shell, "https://example.org/"))
                .contains("https://example.org/rel/x.mp4");
    }

    @Test
    void decodeOver200WiresThroughTheClient() {
        SibnetConfig config = SibnetConfig.builder().build();
        WebClient.Builder builder =
                WebClient.builder()
                        .exchangeFunction(
                                req ->
                                        Mono.just(
                                                ClientResponse.create(HttpStatus.OK)
                                                        .header(
                                                                "Content-Type",
                                                                MediaType.TEXT_HTML_VALUE)
                                                        .body(
                                                                "player.src([{src:\"/v/9/m.mp4\","
                                                                        + " type:\"video/mp4\"}]);")
                                                        .build()));
        SibnetDecoder decoder = new SibnetDecoder(config, builder);

        StepVerifier.create(decoder.decode(9L))
                .assertNext(
                        r -> {
                            assertThat(r.success()).isTrue();
                            assertThat(r.qualities())
                                    .containsEntry("720", "https://video.sibnet.ru/v/9/m.mp4");
                        })
                .verifyComplete();
    }

    @Test
    void decode404IsTranslatedToVideoNotFound() {
        SibnetConfig config = SibnetConfig.builder().build();
        WebClient.Builder builder =
                WebClient.builder()
                        .exchangeFunction(
                                req ->
                                        Mono.just(
                                                ClientResponse.create(HttpStatus.NOT_FOUND)
                                                        .body("Not Found")
                                                        .build()));
        SibnetDecoder decoder = new SibnetDecoder(config, builder);

        StepVerifier.create(decoder.decode(404L))
                .assertNext(
                        r -> {
                            assertThat(r.success()).isFalse();
                            assertThat(r.errorCode())
                                    .isEqualTo(SibnetErrorCodes.SIBNET_VIDEO_NOT_FOUND);
                        })
                .verifyComplete();
    }

    @Test
    void decode500IsTranslatedToFetchError() {
        SibnetConfig config = SibnetConfig.builder().build();
        WebClient.Builder builder =
                WebClient.builder()
                        .exchangeFunction(
                                req ->
                                        Mono.just(
                                                ClientResponse.create(
                                                                HttpStatus.INTERNAL_SERVER_ERROR)
                                                        .body("nginx 500")
                                                        .build()));
        SibnetDecoder decoder = new SibnetDecoder(config, builder);

        StepVerifier.create(decoder.decode("https://video.sibnet.ru/shell.php?videoid=500"))
                .assertNext(
                        r -> {
                            assertThat(r.success()).isFalse();
                            assertThat(r.errorCode())
                                    .isEqualTo(SibnetErrorCodes.SIBNET_FETCH_ERROR);
                        })
                .verifyComplete();
    }
}
