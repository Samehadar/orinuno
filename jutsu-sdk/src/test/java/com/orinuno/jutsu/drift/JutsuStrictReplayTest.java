package com.orinuno.jutsu.drift;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.orinuno.jutsu.catalog.JutsuCatalogParser;
import com.orinuno.jutsu.episode.JutsuEpisodePageParser;
import com.orinuno.jutsu.filter.JutsuFilterFormParser;
import com.orinuno.jutsu.info.JutsuAnimeInfoParser;
import com.orinuno.jutsu.notice.JutsuNoticeParser;
import com.orinuno.jutsu.parser.JutsuHtmlCharset;
import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Strict-mode fixture replay: each parser is run against its captured baseline fixture in strict
 * mode (drift events promote to {@link JutsuDriftException}). A passing test means the orinuno-app
 * scheduled canary probe can run in strict mode against a freshly-fetched live page with zero false
 * positives.
 *
 * <p>If the live HTML at jut.su drifts away from the baseline, the corresponding fixture must be
 * recaptured first (see {@code src/test/resources/jutsu/README.md}); the recapture itself counts as
 * the diff that documents what changed and why.
 */
class JutsuStrictReplayTest {

    private static String loadFixture(String name) throws IOException {
        try (InputStream stream =
                Objects.requireNonNull(
                        JutsuStrictReplayTest.class.getResourceAsStream("/jutsu/" + name),
                        "fixture " + name + " not on classpath")) {
            return JutsuHtmlCharset.decode(stream.readAllBytes(), null);
        }
    }

    private static JutsuParserContext strict(JutsuDriftDetector detector, String source) {
        return JutsuParserContext.strict(detector, source, "test-fixture");
    }

    static Stream<Arguments> catalogFixtures() {
        return Stream.of(
                Arguments.of("catalog_page2_anime.html", 2),
                Arguments.of("catalog_filter_comedy_2024.html", 1),
                Arguments.of("catalog_search_history.html", 1));
    }

    @ParameterizedTest(name = "catalog parser strict-replay: {0}")
    @MethodSource("catalogFixtures")
    void catalogParserParsesFixtureWithoutStrictDrift(String fixture, int page) throws IOException {
        String html = loadFixture(fixture);
        JutsuDriftDetector detector = new JutsuDriftDetector();

        assertThatCode(
                        () ->
                                new JutsuCatalogParser(strict(detector, "JutsuCatalogParser"))
                                        .parse(html, page))
                .as("strict-mode catalog parse must not raise drift on %s", fixture)
                .doesNotThrowAnyException();
        assertThat(detector.snapshot().recentEvents()).isEmpty();
    }

    @Test
    void filterFormParserParsesFixtureWithoutStrictDrift() throws IOException {
        String html = loadFixture("anime_filter_form.html");
        JutsuDriftDetector detector = new JutsuDriftDetector();

        assertThatCode(
                        () ->
                                new JutsuFilterFormParser(strict(detector, "JutsuFilterFormParser"))
                                        .parse(html))
                .as("strict-mode filter form parse must not raise drift")
                .doesNotThrowAnyException();
        assertThat(detector.snapshot().recentEvents()).isEmpty();
    }

    @Test
    void animeInfoParserParsesFixtureWithoutStrictDrift() throws IOException {
        String html = loadFixture("anime_info_onepunch.html");
        JutsuDriftDetector detector = new JutsuDriftDetector();

        assertThatCode(
                        () ->
                                new JutsuAnimeInfoParser(strict(detector, "JutsuAnimeInfoParser"))
                                        .parse(html, "onepuunchman"))
                .as("strict-mode anime info parse must not raise drift")
                .doesNotThrowAnyException();
        assertThat(detector.snapshot().recentEvents()).isEmpty();
    }

    @Test
    void episodePageParserParsesFixtureWithoutStrictDrift() throws IOException {
        String html = loadFixture("episode_premium_gated.html");
        JutsuDriftDetector detector = new JutsuDriftDetector();

        assertThatCode(
                        () ->
                                new JutsuEpisodePageParser(
                                                strict(detector, "JutsuEpisodePageParser"))
                                        .parse(html, "/onepuunchman/season-1/episode-1.html"))
                .as("strict-mode episode page parse must not raise drift")
                .doesNotThrowAnyException();
        assertThat(detector.snapshot().recentEvents()).isEmpty();
    }

    @Test
    void noticeParserParsesFixtureWithoutStrictDrift() throws IOException {
        String html = loadFixture("notice_feed_18729.html");
        JutsuDriftDetector detector = new JutsuDriftDetector();

        assertThatCode(
                        () ->
                                new JutsuNoticeParser(strict(detector, "JutsuNoticeParser"))
                                        .parse(html, 18729))
                .as("strict-mode notice feed parse must not raise drift")
                .doesNotThrowAnyException();
        assertThat(detector.snapshot().recentEvents()).isEmpty();
    }

    @Test
    void noticeParserHistoryBoundIsStrictClean() {
        // 0-byte response is the legitimate history-bound signal — must NOT trip strict mode.
        JutsuDriftDetector detector = new JutsuDriftDetector();

        assertThatCode(
                        () ->
                                new JutsuNoticeParser(strict(detector, "JutsuNoticeParser"))
                                        .parse("", 0))
                .as("history-bound (empty body) is legitimate, not drift")
                .doesNotThrowAnyException();
        assertThat(detector.snapshot().recentEvents()).isEmpty();
    }
}
