package com.orinuno.service.provider.jutsu;

import static org.assertj.core.api.Assertions.assertThat;

import com.orinuno.service.provider.ProviderDecodeResult;
import com.orinuno.service.provider.jutsu.JutsuSourceParser.JutsuEpisodeRef;
import org.junit.jupiter.api.Test;

class JutsuDecoderServiceTest {

    @Test
    void extractsTwoQualitiesFromTypicalPage() {
        String html =
                "<video><source src=\"https://video.jut.su/dn/720.mp4\" type=\"video/mp4\""
                        + " label=\"720p\"><source src=\"https://video.jut.su/dn/1080.mp4\""
                        + " type=\"video/mp4\" label=\"1080p\"></video>";
        ProviderDecodeResult r = JutsuDecoderService.extractFromHtml(html);
        assertThat(r.success()).isTrue();
        assertThat(r.qualities())
                .containsEntry("720", "https://video.jut.su/dn/720.mp4")
                .containsEntry("1080", "https://video.jut.su/dn/1080.mp4");
        assertThat(r.format()).isEqualTo("video/mp4");
    }

    @Test
    void quality720FromUrlWhenLabelMissing() {
        String html =
                "<video><source src=\"https://video.jut.su/dn/720.mp4\""
                        + " type=\"video/mp4\"></video>";
        ProviderDecodeResult r = JutsuDecoderService.extractFromHtml(html);
        assertThat(r.qualities()).containsEntry("720", "https://video.jut.su/dn/720.mp4");
    }

    @Test
    void noSourceTagButPremiumMarkerIsPremiumRequired() {
        ProviderDecodeResult r =
                JutsuDecoderService.extractFromHtml(
                        "<html><div class=\"premium-gate\">Только для премиум</div></html>");
        assertThat(r.errorCode()).isEqualTo("JUTSU_PREMIUM_REQUIRED");
    }

    @Test
    void cloudflareChallengeIsTransient() {
        ProviderDecodeResult r =
                JutsuDecoderService.extractFromHtml(
                        "<html><body>Just a moment...<div"
                                + " id=\"cf-browser-verification\"/></body></html>");
        assertThat(r.errorCode()).isEqualTo("JUTSU_CLOUDFLARE_BLOCKED");
    }

    @Test
    void noSourceTagAndNoPremiumIsSourceTagMissing() {
        assertThat(JutsuDecoderService.extractFromHtml("<html>nothing</html>").errorCode())
                .isEqualTo("JUTSU_SOURCE_TAG_MISSING");
    }

    @Test
    void emptyHtmlIsEmptyResponse() {
        assertThat(JutsuDecoderService.extractFromHtml("").errorCode())
                .isEqualTo("JUTSU_EMPTY_RESPONSE");
        assertThat(JutsuDecoderService.extractFromHtml(null).errorCode())
                .isEqualTo("JUTSU_EMPTY_RESPONSE");
    }

    @Test
    void pickQualityPrefersLabelOverUrl() {
        assertThat(JutsuDecoderService.pickQuality("480p", "https://x/something.mp4"))
                .isEqualTo("480");
        assertThat(JutsuDecoderService.pickQuality(null, "https://x/dn/720.mp4")).isEqualTo("720");
        assertThat(JutsuDecoderService.pickQuality("HD", "https://x/no-quality.mp4"))
                .isEqualTo("auto");
    }

    @Test
    void sourceParserExtracts() {
        assertThat(JutsuSourceParser.extractRef("https://jut.su/naruto/episode-12.html"))
                .contains(new JutsuEpisodeRef("naruto", 12));
        assertThat(
                        JutsuSourceParser.extractRef(
                                "https://jut.su/anime-fullmetal/season-2/episode-5.html"))
                .contains(new JutsuEpisodeRef("anime-fullmetal", 5));
        assertThat(JutsuSourceParser.extractRef("https://jut.su/naruto/")).isEmpty();
        assertThat(JutsuSourceParser.extractRef(null)).isEmpty();
    }
}
