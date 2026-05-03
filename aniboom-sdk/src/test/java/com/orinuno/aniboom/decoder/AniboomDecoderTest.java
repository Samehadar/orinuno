package com.orinuno.aniboom.decoder;

import static org.assertj.core.api.Assertions.assertThat;

import com.orinuno.aniboom.AniboomConfig;
import com.orinuno.aniboom.AniboomDecodeResult;
import com.orinuno.aniboom.AniboomErrorCodes;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class AniboomDecoderTest {

    private final AniboomDecoder service =
            new AniboomDecoder(AniboomConfig.builder().build(), WebClient.builder());

    @Test
    void parsesEmbedHtmlWithBothHlsAndDash() {
        String html =
                "<html><body><input id=\"video-data\" data-parameters=\"{&quot;hls&quot;:"
                        + "&quot;https://cdn.aniboom.one/m.m3u8&quot;,&quot;dash&quot;:"
                        + "&quot;https://cdn.aniboom.one/m.mpd&quot;}\" /></body></html>";

        AniboomDecodeResult r = service.extractFromHtml(html);
        assertThat(r.success()).isTrue();
        assertThat(r.qualities())
                .containsEntry("auto", "https://cdn.aniboom.one/m.m3u8")
                .containsEntry("dash", "https://cdn.aniboom.one/m.mpd");
        assertThat(r.format()).isEqualTo("application/x-mpegURL");
    }

    @Test
    void parsesHlsOnly() {
        String html =
                "<input id=\"video-data\""
                    + " data-parameters=\"{&quot;hls&quot;:&quot;https://cdn/m.m3u8&quot;}\" />";
        AniboomDecodeResult r = service.extractFromHtml(html);
        assertThat(r.success()).isTrue();
        assertThat(r.format()).isEqualTo("application/x-mpegURL");
    }

    @Test
    void parsesDashOnly() {
        String html =
                "<input id=\"video-data\""
                    + " data-parameters=\"{&quot;dash&quot;:&quot;https://cdn/m.mpd&quot;}\" />";
        AniboomDecodeResult r = service.extractFromHtml(html);
        assertThat(r.success()).isTrue();
        assertThat(r.format()).isEqualTo("application/dash+xml");
    }

    @Test
    void emptyParametersIsGeoBlocked() {
        String html = "<input id=\"video-data\" data-parameters=\"{}\" />";
        AniboomDecodeResult r = service.extractFromHtml(html);
        assertThat(r.success()).isFalse();
        assertThat(r.errorCode()).isEqualTo(AniboomErrorCodes.ANIBOOM_GEO_BLOCKED);
    }

    @Test
    void missingInputIsDataInputMissing() {
        AniboomDecodeResult r = service.extractFromHtml("<html>nothing here</html>");
        assertThat(r.errorCode()).isEqualTo(AniboomErrorCodes.ANIBOOM_DATA_INPUT_MISSING);
    }

    @Test
    void nullHtmlIsDataInputMissing() {
        assertThat(service.extractFromHtml(null).errorCode())
                .isEqualTo(AniboomErrorCodes.ANIBOOM_DATA_INPUT_MISSING);
        assertThat(service.extractFromHtml("").errorCode())
                .isEqualTo(AniboomErrorCodes.ANIBOOM_DATA_INPUT_MISSING);
    }

    @Test
    void noPlaylistKeysIsNoPlaylist() {
        String html =
                "<input id=\"video-data\""
                    + " data-parameters=\"{&quot;subtitle_url&quot;:&quot;https://x/sub.vtt&quot;}\""
                    + " />";
        AniboomDecodeResult r = service.extractFromHtml(html);
        assertThat(r.errorCode()).isEqualTo(AniboomErrorCodes.ANIBOOM_NO_PLAYLIST);
    }

    @Test
    void htmlEntityDecodeHandlesCommonEntities() {
        assertThat(AniboomDecoder.htmlEntityDecode("&quot;a&quot; &amp; &lt;b&gt; &#39;c&#39;"))
                .isEqualTo("\"a\" & <b> 'c'");
        assertThat(AniboomDecoder.htmlEntityDecode(null)).isEmpty();
    }

    @Test
    void decode200WiresThroughTheClient() {
        AniboomConfig config = AniboomConfig.builder().build();
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
                                                                "<input id=\"video-data\""
                                                                    + " data-parameters=\"{&quot;hls&quot;:"
                                                                    + "&quot;https://cdn/m.m3u8&quot;}\""
                                                                    + " />")
                                                        .build()));
        AniboomDecoder decoder = new AniboomDecoder(config, builder);

        StepVerifier.create(decoder.decode("https://aniboom.one/embed/abc"))
                .assertNext(
                        r -> {
                            assertThat(r.success()).isTrue();
                            assertThat(r.qualities()).containsEntry("auto", "https://cdn/m.m3u8");
                        })
                .verifyComplete();
    }

    @Test
    void decode500IsTranslatedToFetchError() {
        AniboomConfig config = AniboomConfig.builder().build();
        WebClient.Builder builder =
                WebClient.builder()
                        .exchangeFunction(
                                req ->
                                        Mono.just(
                                                ClientResponse.create(
                                                                HttpStatus.INTERNAL_SERVER_ERROR)
                                                        .body("nginx 500")
                                                        .build()));
        AniboomDecoder decoder = new AniboomDecoder(config, builder);

        StepVerifier.create(decoder.decode("https://aniboom.one/embed/abc"))
                .assertNext(
                        r -> {
                            assertThat(r.success()).isFalse();
                            assertThat(r.errorCode())
                                    .isEqualTo(AniboomErrorCodes.ANIBOOM_FETCH_ERROR);
                        })
                .verifyComplete();
    }
}
