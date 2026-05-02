package com.orinuno.service.provider.sibnet;

import static org.assertj.core.api.Assertions.assertThat;

import com.orinuno.service.provider.ProviderDecodeResult;
import org.junit.jupiter.api.Test;

class SibnetDecoderServiceTest {

    @Test
    void extractsFromTypicalShellHtml() {
        String html =
                "<html><body><script>"
                        + "var player = videojs('video');\n"
                        + "player.src([{src: \"/v/123/master.mp4\", type: \"video/mp4\"}]);"
                        + "</script></body></html>";

        ProviderDecodeResult r =
                SibnetDecoderService.extractFromHtml(
                        html, "https://video.sibnet.ru/shell.php?videoid=123");

        assertThat(r.success()).isTrue();
        assertThat(r.qualities()).containsEntry("720", "https://video.sibnet.ru/v/123/master.mp4");
        assertThat(r.format()).isEqualTo("video/mp4");
    }

    @Test
    void extractsAbsoluteHttpsUrl() {
        String html =
                "player.src([{src:\"https://cdn.sibnet.ru/abs/master.mp4\", type:\"video/mp4\"}]);";
        ProviderDecodeResult r =
                SibnetDecoderService.extractFromHtml(
                        html, "https://video.sibnet.ru/shell.php?videoid=1");
        assertThat(r.qualities()).containsEntry("720", "https://cdn.sibnet.ru/abs/master.mp4");
    }

    @Test
    void extractsProtocolRelativeUrl() {
        String html = "player.src([{src:\"//cdn.sibnet.ru/p.mp4\", type:\"video/mp4\"}]);";
        ProviderDecodeResult r =
                SibnetDecoderService.extractFromHtml(
                        html, "https://video.sibnet.ru/shell.php?videoid=1");
        assertThat(r.qualities()).containsEntry("720", "https://cdn.sibnet.ru/p.mp4");
    }

    @Test
    void failsOnRegexMiss() {
        ProviderDecodeResult r =
                SibnetDecoderService.extractFromHtml(
                        "<html>no player here</html>",
                        "https://video.sibnet.ru/shell.php?videoid=1");
        assertThat(r.success()).isFalse();
        assertThat(r.errorCode()).isEqualTo("SIBNET_PLAYER_REGEX_BREAK");
    }

    @Test
    void failsOnEmptyHtml() {
        assertThat(SibnetDecoderService.extractFromHtml("", "u").errorCode())
                .isEqualTo("SIBNET_PLAYER_REGEX_BREAK");
        assertThat(SibnetDecoderService.extractFromHtml(null, "u").errorCode())
                .isEqualTo("SIBNET_PLAYER_REGEX_BREAK");
    }

    @Test
    void absolutizeShapes() {
        String shell = "https://video.sibnet.ru/shell.php?videoid=1";
        assertThat(SibnetDecoderService.absolutize("/v/x.mp4", shell))
                .contains("https://video.sibnet.ru/v/x.mp4");
        assertThat(SibnetDecoderService.absolutize("//cdn/x.mp4", shell))
                .contains("https://cdn/x.mp4");
        assertThat(SibnetDecoderService.absolutize("https://full/x.mp4", shell))
                .contains("https://full/x.mp4");
        assertThat(SibnetDecoderService.absolutize("rel/x.mp4", shell))
                .contains("https://video.sibnet.ru/rel/x.mp4");
        assertThat(SibnetDecoderService.absolutize(null, shell)).isEmpty();
    }
}
