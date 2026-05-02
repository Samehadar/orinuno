package com.orinuno.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Regression tests for {@link KodikVideoDecoderService}'s static patterns.
 *
 * <p>Each fixture is a real iframe HTML snippet captured during the 2026-05-02 probe (see
 * docs/research/2026-05-02-api-and-decoder-probe.md and docs/quirks-and-hacks.md "Player JS file
 * naming is now type-dependent"). If Kodik introduces a fourth naming variant, add a row here so we
 * catch it before production fails.
 */
class KodikVideoDecoderRegexTest {

    @ParameterizedTest(name = "PLAYER_JS_PATTERN matches {0}")
    @CsvSource(
            value = {
                "legacy_movie    | <script"
                    + " src=\"/assets/js/app.player_single.a037e914ff071ae9f9233262812e33406962878760287f5f77c8becbcc8bf5b0.js\"></script>"
                    + " | assets/js/app.player_single.a037e914ff071ae9f9233262812e33406962878760287f5f77c8becbcc8bf5b0.js",
                "serial_2026_05  | <script"
                    + " src=\"/assets/js/app.serial.b0696c73b42bb27284100d3a7065607cc36010a8ab45fd39554aae5f88829a62.js\"></script>"
                    + "        |"
                    + " assets/js/app.serial.b0696c73b42bb27284100d3a7065607cc36010a8ab45fd39554aae5f88829a62.js",
                "future_player   | <script"
                    + " src=\"/assets/js/app.player.0e6d221137bbca3b7eb04533cce7f66e55102d050229874f854cc2fdf2cb456b.js\"></script>"
                    + "        |"
                    + " assets/js/app.player.0e6d221137bbca3b7eb04533cce7f66e55102d050229874f854cc2fdf2cb456b.js",
                "with_attrs      | <script type=\"text/javascript\" "
                    + " src=\"/assets/js/app.serial.aaa.js\"></script>                             "
                    + "                   | assets/js/app.serial.aaa.js"
            },
            delimiter = '|')
    @DisplayName(
            "PLAYER_JS_PATTERN must match all observed Kodik 2026-05 player-js naming variants"
                    + " (regression for the silent-decode-failure-on-serial bug)")
    void playerJsPatternMatchesAllKnownIframeVariants(
            String label, String iframeFragment, String expectedGroup1) throws Exception {
        Pattern pattern = readPrivateStaticPattern("PLAYER_JS_PATTERN");
        Matcher m = pattern.matcher(iframeFragment.trim());
        assertThat(m.find())
                .as(
                        "[%s] PLAYER_JS_PATTERN regression — fragment `%s` did NOT match the"
                                + " current regex; see docs/quirks-and-hacks.md",
                        label, iframeFragment.trim())
                .isTrue();
        assertThat(m.group(1))
                .as("[%s] expected group(1) = player JS path under /assets/js/", label)
                .isEqualTo(expectedGroup1.trim());
    }

    @ParameterizedTest(name = "PLAYER_JS_PATTERN does NOT match unrelated {0}")
    @CsvSource(
            value = {
                "css_link        | <link rel=\"stylesheet\""
                    + " href=\"/assets/css/app.player.84bab7644657e6048a57fdc6185d50b0d1caf81daabc939abd51641b5cb7ea6b.css\""
                    + " />",
                "external_js     | <script"
                    + " src=\"https://cdn.jsdelivr.net/npm/yandex-metrica-watch/tag.js\"></script>",
                "ads_js          | <script src=\"/adsbygoogle.js\"></script>",
                "favicon         | <link href=\"/assets/images/favicon.png\" rel=\"shortcut icon\">"
            },
            delimiter = '|')
    @DisplayName("PLAYER_JS_PATTERN must NOT match unrelated assets")
    void playerJsPatternIsScopedToAppAssets(String label, String fragment) throws Exception {
        Pattern pattern = readPrivateStaticPattern("PLAYER_JS_PATTERN");
        assertThat(pattern.matcher(fragment.trim()).find())
                .as("[%s] regex must not over-match unrelated `%s`", label, fragment.trim())
                .isFalse();
    }

    @org.junit.jupiter.api.Test
    @DisplayName(
            "POST_URL_PATTERN must extract base64-encoded path from real player-js snippets"
                    + " (regression for video-info-path discovery)")
    void postUrlPatternExtractsAtobPath() throws Exception {
        Pattern pattern = readPrivateStaticPattern("POST_URL_PATTERN");
        String snippet = "...$.ajax({type:\"POST\",url:atob(\"L2Z0b3I=\"),data:postdata...";
        Matcher m = pattern.matcher(snippet);
        assertThat(m.find()).as("real player-js fragment must match").isTrue();
        assertThat(m.group(1)).isEqualTo("L2Z0b3I=");
    }

    @org.junit.jupiter.api.Test
    @DisplayName("URL_PARAMS / TYPE / HASH / ID patterns extract from real iframe fragment")
    void vInfoExtractionPatterns() throws Exception {
        String fragment =
                "    var urlParams ="
                        + " '{\"d\":\"kodik.cc\",\"d_sign\":\"abc:123\",\"first_url\":false}'; \n"
                        + "   vInfo.type = 'seria'; \n"
                        + "   vInfo.hash = 'd5f562140abc6aea2eb04848426133c2'; \n"
                        + "   vInfo.id = '176457'; \n";
        assertThat(extractGroup1("URL_PARAMS_PATTERN", fragment))
                .isEqualTo("{\"d\":\"kodik.cc\",\"d_sign\":\"abc:123\",\"first_url\":false}");
        assertThat(extractGroup1("TYPE_PATTERN", fragment)).isEqualTo("seria");
        assertThat(extractGroup1("HASH_PATTERN", fragment))
                .isEqualTo("d5f562140abc6aea2eb04848426133c2");
        assertThat(extractGroup1("ID_PATTERN", fragment)).isEqualTo("176457");
    }

    private static Pattern readPrivateStaticPattern(String name) throws Exception {
        Field f = KodikVideoDecoderService.class.getDeclaredField(name);
        f.setAccessible(true);
        return (Pattern) f.get(null);
    }

    private static String extractGroup1(String patternFieldName, String input) throws Exception {
        Matcher m = readPrivateStaticPattern(patternFieldName).matcher(input);
        return m.find() ? m.group(1) : null;
    }
}
