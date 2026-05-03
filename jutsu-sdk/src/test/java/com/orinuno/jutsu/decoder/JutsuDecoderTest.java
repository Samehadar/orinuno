package com.orinuno.jutsu.decoder;

import static org.assertj.core.api.Assertions.assertThat;

import com.orinuno.jutsu.JutsuDecodeResult;
import com.orinuno.jutsu.JutsuErrorCodes;
import com.orinuno.jutsu.parser.JutsuSourceParser;
import com.orinuno.jutsu.parser.JutsuSourceParser.JutsuEpisodeRef;
import org.junit.jupiter.api.Test;

class JutsuDecoderTest {

    @Test
    void extractsTwoQualitiesFromTypicalPage() {
        String html =
                "<video><source src=\"https://video.jut.su/dn/720.mp4\" type=\"video/mp4\""
                        + " label=\"720p\"><source src=\"https://video.jut.su/dn/1080.mp4\""
                        + " type=\"video/mp4\" label=\"1080p\"></video>";
        JutsuDecodeResult r = JutsuDecoder.extractFromHtml(html);
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
        JutsuDecodeResult r = JutsuDecoder.extractFromHtml(html);
        assertThat(r.qualities()).containsEntry("720", "https://video.jut.su/dn/720.mp4");
    }

    @Test
    void premiumAccountAllFourQualitiesExtractedFromYandexCdnSnapshot() {
        // Trimmed snapshot of the live response when authenticated as a Jutsu+ account. The new
        // URL shape interleaves digits with dots ({episode}.{quality}.{hash}.mp4) and the tag has
        // 6+ attributes in a fixed order, the previous combined regex silently dropped 3 of 4
        // sources and labelled the survivor as "auto". This test pins the regression.
        String html =
                "<video class=\"video-js\"><source"
                    + " src=\"https://r420501.yandexwebcache.org/dead-dead-demons/4.1080.5653afffdacc4324.mp4?derou=3829047&hash=ABC\""
                    + " type=\"video/mp4\" lang=\"ru\" label=\"1080p\" res=\"1080\"/><source"
                    + " src=\"https://r420501.yandexwebcache.org/dead-dead-demons/4.720.8c2f11ef34b5809e.mp4?derou=3829047&hash=DEF\""
                    + " type=\"video/mp4\" lang=\"ru\" label=\"720p\" res=\"720\"/><source"
                    + " src=\"https://r420501.yandexwebcache.org/dead-dead-demons/4.480.0b71e025cd09ea5d.mp4?derou=3829047&hash=GHI\""
                    + " type=\"video/mp4\" lang=\"ru\" label=\"480p\" res=\"480\""
                    + " selected=\"true\"/><source"
                    + " src=\"https://r420501.yandexwebcache.org/dead-dead-demons/4.360.edd99115c1bf662e.mp4?derou=3829047&hash=JKL\""
                    + " type=\"video/mp4\" lang=\"ru\" label=\"360p\" res=\"360\"/></video>";
        JutsuDecodeResult r = JutsuDecoder.extractFromHtml(html);
        assertThat(r.success()).isTrue();
        assertThat(r.qualities())
                .containsKeys("1080", "720", "480", "360")
                .doesNotContainKey("auto")
                .hasSize(4);
        assertThat(r.qualities().get("1080")).contains("4.1080.").contains("derou=3829047");
        assertThat(r.format()).isEqualTo("video/mp4");
    }

    @Test
    void resAttributeUsedWhenLabelIsCosmetic() {
        // jut.su occasionally ships labels like "HD" instead of "1080p" — fall through to res.
        String html =
                "<source src=\"https://x/y/4.1080.deadbeef.mp4\""
                        + " type=\"video/mp4\" label=\"HD\" res=\"1080\"/>";
        JutsuDecodeResult r = JutsuDecoder.extractFromHtml(html);
        assertThat(r.qualities()).containsKey("1080");
    }

    @Test
    void quotedAttributesInArbitraryOrderStillExtract() {
        String html =
                "<source label=\"720p\" type=\"video/mp4\""
                        + " src=\"https://x/y/4.720.aaa.mp4\" res=\"720\" lang=\"ru\"/>";
        JutsuDecodeResult r = JutsuDecoder.extractFromHtml(html);
        assertThat(r.qualities()).containsEntry("720", "https://x/y/4.720.aaa.mp4");
    }

    @Test
    void nonMp4SourceIsIgnored() {
        // <source> tags for poster images / .vtt subtitles / .webm fallbacks must not pollute
        // the quality map. Regex-level filter at the URL-attribute level guarantees this.
        String html =
                "<video><source src=\"https://x/y/cover.png\" type=\"image/png\"/>"
                        + "<source src=\"https://x/y/720.mp4\" type=\"video/mp4\""
                        + " label=\"720p\"/></video>";
        JutsuDecodeResult r = JutsuDecoder.extractFromHtml(html);
        assertThat(r.qualities()).containsKeys("720").hasSize(1);
    }

    @Test
    void tabNeedPlusOverlayIsPremiumRequired() {
        JutsuDecodeResult r =
                JutsuDecoder.extractFromHtml(
                        "<div class=\"top_player_line\"><div class=\"tab_need_plus\">"
                                + "<a href=\"/plus/\">Jutsu+</a></div></div>");
        assertThat(r.errorCode()).isEqualTo(JutsuErrorCodes.JUTSU_PREMIUM_REQUIRED);
    }

    @Test
    void pixelPngPlaceholderInSourceIsPremiumRequired() {
        // jut.su substitutes pixel.png for the real CDN URL on gated episodes — even when the
        // <source> tags themselves still look well-formed. We catch that before regex extraction.
        String html =
                "<video><source src=\"https://gen.jut.su/templates/school/images/pixel.png?720\""
                        + " type=\"video/mp4\" label=\"720p\"></video>";
        JutsuDecodeResult r = JutsuDecoder.extractFromHtml(html);
        assertThat(r.errorCode()).isEqualTo(JutsuErrorCodes.JUTSU_PREMIUM_REQUIRED);
    }

    @Test
    void realDeadDeadDemonsSnapshotIsPremiumRequired() {
        // Trimmed snapshot of a real jut.su premium-gated episode response (dead-dead-demons,
        // ep4). Both the tab_need_plus overlay AND the pixel.png placeholder URLs appear; either
        // marker on its own is enough but we want the regression test to mirror production.
        String html =
                "<html><body><div class=\"post_media pm_videojs\"><div"
                    + " class=\"top_player_line\"><div class=\"tab_need_plus\"><div"
                    + " class=\"tab_need_plus_text\"><span><a"
                    + " href=\"/plus/\">Jutsu+</a></span></div></div></div><video class=\"video-js"
                    + " vjs-default-skin\"><source"
                    + " src=\"https://gen.jut.su/templates/school/images/pixel.png?1080\""
                    + " type=\"video/mp4\" label=\"1080p\"/><source"
                    + " src=\"https://gen.jut.su/templates/school/images/pixel.png?720\""
                    + " type=\"video/mp4\" label=\"720p\"/></video></div></body></html>";
        JutsuDecodeResult r = JutsuDecoder.extractFromHtml(html);
        assertThat(r.errorCode()).isEqualTo(JutsuErrorCodes.JUTSU_PREMIUM_REQUIRED);
    }

    @Test
    void cloudflareChallengeIsTransient() {
        JutsuDecodeResult r =
                JutsuDecoder.extractFromHtml(
                        "<html><body>Just a moment...<div"
                                + " id=\"cf-browser-verification\"/></body></html>");
        assertThat(r.errorCode()).isEqualTo(JutsuErrorCodes.JUTSU_CLOUDFLARE_BLOCKED);
    }

    @Test
    void htmlWithoutPlayerBlockIsPlayerMissing() {
        // Bot-detection mode: page returns minimal HTML without the <video> player block.
        // Distinguishes "real upstream change" from "we're being filtered for missing cookies".
        JutsuDecodeResult r =
                JutsuDecoder.extractFromHtml(
                        "<html><body><nav>links</nav><footer>x</footer></body></html>");
        assertThat(r.errorCode()).isEqualTo(JutsuErrorCodes.JUTSU_PLAYER_MISSING);
    }

    @Test
    void htmlWithPlayerButNoMp4SourcesIsSourceTagMissing() {
        // Player block present but no <source src="….mp4"> matched — schema drift territory.
        JutsuDecodeResult r =
                JutsuDecoder.extractFromHtml(
                        "<video class=\"video-js\"><source src=\"x.webm\""
                                + " type=\"video/webm\"/></video>");
        assertThat(r.errorCode()).isEqualTo(JutsuErrorCodes.JUTSU_SOURCE_TAG_MISSING);
    }

    @Test
    void emptyHtmlIsEmptyResponse() {
        assertThat(JutsuDecoder.extractFromHtml("").errorCode())
                .isEqualTo(JutsuErrorCodes.JUTSU_EMPTY_RESPONSE);
        assertThat(JutsuDecoder.extractFromHtml(null).errorCode())
                .isEqualTo(JutsuErrorCodes.JUTSU_EMPTY_RESPONSE);
    }

    @Test
    void pickQualityPrefersLabelOverUrl() {
        assertThat(JutsuDecoder.pickQuality("480p", "https://x/something.mp4")).isEqualTo("480");
        assertThat(JutsuDecoder.pickQuality(null, "https://x/dn/720.mp4")).isEqualTo("720");
        assertThat(JutsuDecoder.pickQuality("HD", "https://x/no-quality.mp4")).isEqualTo("auto");
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
