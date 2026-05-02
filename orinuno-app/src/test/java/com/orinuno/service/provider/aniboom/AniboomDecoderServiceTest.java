package com.orinuno.service.provider.aniboom;

import static org.assertj.core.api.Assertions.assertThat;

import com.orinuno.client.http.RotatingUserAgentProvider;
import com.orinuno.service.provider.ProviderDecodeResult;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

class AniboomDecoderServiceTest {

    private final AniboomDecoderService service =
            new AniboomDecoderService(WebClient.builder(), new RotatingUserAgentProvider());

    @Test
    void parsesEmbedHtmlWithBothHlsAndDash() {
        String html =
                "<html><body><input id=\"video-data\" data-parameters=\"{&quot;hls&quot;:"
                        + "&quot;https://cdn.aniboom.one/m.m3u8&quot;,&quot;dash&quot;:"
                        + "&quot;https://cdn.aniboom.one/m.mpd&quot;}\" /></body></html>";

        ProviderDecodeResult r = service.extractFromHtml(html);
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
        ProviderDecodeResult r = service.extractFromHtml(html);
        assertThat(r.success()).isTrue();
        assertThat(r.format()).isEqualTo("application/x-mpegURL");
    }

    @Test
    void parsesDashOnly() {
        String html =
                "<input id=\"video-data\""
                    + " data-parameters=\"{&quot;dash&quot;:&quot;https://cdn/m.mpd&quot;}\" />";
        ProviderDecodeResult r = service.extractFromHtml(html);
        assertThat(r.success()).isTrue();
        assertThat(r.format()).isEqualTo("application/dash+xml");
    }

    @Test
    void emptyParametersIsGeoBlocked() {
        String html = "<input id=\"video-data\" data-parameters=\"{}\" />";
        ProviderDecodeResult r = service.extractFromHtml(html);
        assertThat(r.success()).isFalse();
        assertThat(r.errorCode()).isEqualTo("ANIBOOM_GEO_BLOCKED");
    }

    @Test
    void missingInputIsDataInputMissing() {
        ProviderDecodeResult r = service.extractFromHtml("<html>nothing here</html>");
        assertThat(r.errorCode()).isEqualTo("ANIBOOM_DATA_INPUT_MISSING");
    }

    @Test
    void nullHtmlIsDataInputMissing() {
        assertThat(service.extractFromHtml(null).errorCode())
                .isEqualTo("ANIBOOM_DATA_INPUT_MISSING");
        assertThat(service.extractFromHtml("").errorCode()).isEqualTo("ANIBOOM_DATA_INPUT_MISSING");
    }

    @Test
    void noPlaylistKeysIsNoPlaylist() {
        String html =
                "<input id=\"video-data\""
                    + " data-parameters=\"{&quot;subtitle_url&quot;:&quot;https://x/sub.vtt&quot;}\""
                    + " />";
        ProviderDecodeResult r = service.extractFromHtml(html);
        assertThat(r.errorCode()).isEqualTo("ANIBOOM_NO_PLAYLIST");
    }

    @Test
    void htmlEntityDecodeHandlesCommonEntities() {
        assertThat(
                        AniboomDecoderService.htmlEntityDecode(
                                "&quot;a&quot; &amp; &lt;b&gt; &#39;c&#39;"))
                .isEqualTo("\"a\" & <b> 'c'");
        assertThat(AniboomDecoderService.htmlEntityDecode(null)).isEmpty();
    }

    @Test
    void aniboomSourceParserExtracts() {
        assertThat(AniboomSourceParser.extractEmbedId("https://aniboom.one/embed/abc123"))
                .contains("abc123");
        assertThat(AniboomSourceParser.extractEmbedId("https://kodik.cc/x")).isEmpty();
        assertThat(AniboomSourceParser.toEmbedUrl("xyz"))
                .isEqualTo("https://aniboom.one/embed/xyz");
    }
}
