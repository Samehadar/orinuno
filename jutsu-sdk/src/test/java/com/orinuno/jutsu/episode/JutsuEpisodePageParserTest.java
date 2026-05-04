package com.orinuno.jutsu.episode;

import static org.assertj.core.api.Assertions.assertThat;

import com.orinuno.jutsu.drift.JutsuDriftDetector;
import com.orinuno.jutsu.drift.JutsuDriftSignal;
import com.orinuno.jutsu.drift.JutsuParserContext;
import com.orinuno.jutsu.parser.JutsuHtmlCharset;
import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;
import org.junit.jupiter.api.Test;

class JutsuEpisodePageParserTest {

    private static String loadFixture(String name) throws IOException {
        try (InputStream stream =
                Objects.requireNonNull(
                        JutsuEpisodePageParserTest.class.getResourceAsStream("/jutsu/" + name),
                        "fixture " + name + " not on classpath")) {
            return JutsuHtmlCharset.decode(stream.readAllBytes(), null);
        }
    }

    private static JutsuParserContext lenient(JutsuDriftDetector detector) {
        return JutsuParserContext.lenient(detector, "JutsuEpisodePageParser");
    }

    @Test
    void parsesPremiumGatedFixtureWithFullMetadata() throws IOException {
        String html = loadFixture("episode_premium_gated.html");
        JutsuDriftDetector detector = new JutsuDriftDetector();

        JutsuEpisodeMeta meta =
                new JutsuEpisodePageParser(lenient(detector))
                        .parse(html, "/onepuunchman/season-1/episode-1.html");

        assertThat(meta).isNotNull();
        assertThat(meta.slug()).isEqualTo("onepuunchman");
        assertThat(meta.season()).isEqualTo(1);
        assertThat(meta.episode()).isEqualTo(1);
        assertThat(meta.displayTitle()).contains("Ванпанчмен").contains("1 серия");
        assertThat(meta.pageTitle()).contains("Jut.su");
        assertThat(meta.canonicalUrl())
                .isEqualTo("https://jut.su/onepuunchman/season-1/episode-1.html");
        assertThat(meta.thumbnailUrl()).isNotNull().endsWith(".jpg");
        // Episode 1 has no previous episode but does have a next.
        assertThat(meta.hasPrev()).isFalse();
        assertThat(meta.prevEpisodeUrl()).isNull();
        assertThat(meta.hasNext()).isTrue();
        assertThat(meta.nextEpisodeUrl()).isEqualTo("/onepuunchman/season-1/episode-2.html");
        assertThat(meta.allEpisodesUrl()).isEqualTo("/onepuunchman/");
        assertThat(meta.premiumGated()).isTrue();
    }

    @Test
    void zeroDriftEventsForCanonicalFixture() throws IOException {
        String html = loadFixture("episode_premium_gated.html");
        JutsuDriftDetector detector = new JutsuDriftDetector();

        new JutsuEpisodePageParser(lenient(detector))
                .parse(html, "/onepuunchman/season-1/episode-1.html");

        assertThat(detector.snapshot().recentEvents())
                .as("captured fixture must parse cleanly without drift signals")
                .isEmpty();
    }

    @Test
    void emptyHtmlObservesEmptyResponseAndReturnsNull() {
        JutsuDriftDetector detector = new JutsuDriftDetector();

        JutsuEpisodeMeta meta = new JutsuEpisodePageParser(lenient(detector)).parse("", "/x/");

        assertThat(meta).isNull();
        assertThat(detector.snapshot().recentEvents())
                .extracting(e -> e.signal())
                .containsExactly(JutsuDriftSignal.EMPTY_RESPONSE);
    }

    @Test
    void htmlWithoutTitleObservesSelectorMissAndReturnsNull() {
        String html = "<html><body><h1>x</h1></body></html>";
        JutsuDriftDetector detector = new JutsuDriftDetector();

        JutsuEpisodeMeta meta =
                new JutsuEpisodePageParser(lenient(detector)).parse(html, "/x/episode-1.html");

        assertThat(meta).isNull();
        assertThat(detector.snapshot().recentEvents())
                .extracting(e -> e.signal())
                .contains(JutsuDriftSignal.SELECTOR_MISS);
    }

    @Test
    void htmlWithoutCanonicalObservesSelectorMissAndReturnsNull() {
        String html = "<html><head><title>x</title></head><body><h1>x</h1></body></html>";
        JutsuDriftDetector detector = new JutsuDriftDetector();

        JutsuEpisodeMeta meta =
                new JutsuEpisodePageParser(lenient(detector)).parse(html, "/x/episode-1.html");

        assertThat(meta).isNull();
        assertThat(detector.snapshot().recentEvents())
                .extracting(e -> e.signal())
                .contains(JutsuDriftSignal.SELECTOR_MISS);
    }

    @Test
    void canonicalMismatchObservesSchemaViolation() {
        String html =
                "<html><head><title>x</title><link rel='canonical'"
                        + " href='https://jut.su/foo/season-1/episode-1.html'/>"
                        + "</head><body><h1>x</h1></body></html>";
        JutsuDriftDetector detector = new JutsuDriftDetector();

        JutsuEpisodeMeta meta =
                new JutsuEpisodePageParser(lenient(detector))
                        .parse(html, "/bar/season-1/episode-1.html");

        // Parse still succeeds against the canonical, but drift is observed.
        assertThat(meta).isNotNull();
        assertThat(meta.slug()).isEqualTo("foo");
        assertThat(detector.snapshot().recentEvents())
                .extracting(e -> e.signal())
                .contains(JutsuDriftSignal.SCHEMA_VIOLATION);
    }

    @Test
    void singleSeasonAnimeUrlYieldsSeason1() {
        String html =
                "<html><head><title>t</title>"
                        + "<link rel='canonical' href='https://jut.su/single/episode-3.html'/>"
                        + "</head><body><h1>Single 3</h1></body></html>";
        JutsuDriftDetector detector = new JutsuDriftDetector();

        JutsuEpisodeMeta meta =
                new JutsuEpisodePageParser(lenient(detector)).parse(html, "/single/episode-3.html");

        assertThat(meta).isNotNull();
        assertThat(meta.slug()).isEqualTo("single");
        assertThat(meta.season()).isEqualTo(1);
        assertThat(meta.episode()).isEqualTo(3);
        assertThat(meta.premiumGated()).isFalse();
    }

    @Test
    void clientUrlNormalisationStripsHostForExpectedComparison() {
        // Indirectly tests JutsuEpisodeMetaClient.toRelative — the comparison is what the parser
        // uses for its drift cross-check.
        assertThat(JutsuEpisodeMetaClient.toRelative("https://jut.su/foo/episode-1.html"))
                .isEqualTo("/foo/episode-1.html");
        assertThat(JutsuEpisodeMetaClient.toAbsolute("/foo/episode-1.html"))
                .isEqualTo("https://jut.su/foo/episode-1.html");
        assertThat(JutsuEpisodeMetaClient.toAbsolute("foo/episode-1.html"))
                .isEqualTo("https://jut.su/foo/episode-1.html");
        assertThat(JutsuEpisodeMetaClient.toAbsolute("https://jut.su/already.html"))
                .isEqualTo("https://jut.su/already.html");
    }
}
