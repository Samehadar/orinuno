package com.orinuno.jutsu.info;

import static org.assertj.core.api.Assertions.assertThat;

import com.orinuno.jutsu.drift.JutsuDriftDetector;
import com.orinuno.jutsu.drift.JutsuDriftSignal;
import com.orinuno.jutsu.drift.JutsuParserContext;
import com.orinuno.jutsu.parser.JutsuHtmlCharset;
import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;
import org.junit.jupiter.api.Test;

class JutsuAnimeInfoParserTest {

    private static String loadFixture(String name) throws IOException {
        try (InputStream stream =
                Objects.requireNonNull(
                        JutsuAnimeInfoParserTest.class.getResourceAsStream("/jutsu/" + name),
                        "fixture " + name + " not on classpath")) {
            return JutsuHtmlCharset.decode(stream.readAllBytes(), null);
        }
    }

    private static JutsuParserContext lenient(JutsuDriftDetector detector) {
        return JutsuParserContext.lenient(detector, "JutsuAnimeInfoParser");
    }

    @Test
    void parsesOnePunchManInfoFromCapturedFixture() throws IOException {
        String html = loadFixture("anime_info_onepunch.html");
        JutsuDriftDetector detector = new JutsuDriftDetector();

        JutsuAnimeInfo info =
                new JutsuAnimeInfoParser(lenient(detector)).parse(html, "onepuunchman");

        assertThat(info).isNotNull();
        assertThat(info.slug()).isEqualTo("onepuunchman");
        assertThat(info.title()).contains("Ванпанчмен");
        assertThat(info.originalTitle()).contains("One Punch Man");
        assertThat(info.thumbnailUrl()).isNotNull();
        assertThat(info.seasons()).hasSizeGreaterThanOrEqualTo(3);
        // The info page advertises 3 seasons; total episode count is well into double digits.
        assertThat(info.totalEpisodeCount()).isGreaterThanOrEqualTo(20);
    }

    @Test
    void seasonsAreOrderedByIndexWithMonotonicEpisodes() throws IOException {
        String html = loadFixture("anime_info_onepunch.html");
        JutsuDriftDetector detector = new JutsuDriftDetector();

        JutsuAnimeInfo info =
                new JutsuAnimeInfoParser(lenient(detector)).parse(html, "onepuunchman");

        assertThat(info).isNotNull();
        // Season indices should be 1, 2, 3, ... in order.
        int prev = 0;
        for (JutsuSeason season : info.seasons()) {
            assertThat(season.index()).isGreaterThan(prev);
            prev = season.index();
            // Episodes within a season should also be monotonically ordered.
            int prevEpisode = 0;
            for (JutsuEpisodeListing listing : season.episodes()) {
                assertThat(listing.episode()).isGreaterThan(prevEpisode);
                assertThat(listing.season()).isEqualTo(season.index());
                assertThat(listing.slug()).isEqualTo("onepuunchman");
                assertThat(listing.url())
                        .contains("/onepuunchman/")
                        .contains("episode-" + listing.episode());
                prevEpisode = listing.episode();
            }
        }
    }

    @Test
    void seasonNamesMatchH2WhenAvailable() throws IOException {
        String html = loadFixture("anime_info_onepunch.html");
        JutsuDriftDetector detector = new JutsuDriftDetector();

        JutsuAnimeInfo info =
                new JutsuAnimeInfoParser(lenient(detector)).parse(html, "onepuunchman");

        assertThat(info).isNotNull();
        // Captured fixture has explicit "1 сезон", "2 сезон", "3 сезон" headings.
        for (JutsuSeason season : info.seasons()) {
            assertThat(season.name()).matches(season.index() + "\\s*(сезон|season).*");
        }
    }

    @Test
    void emptyHtmlObservesEmptyResponseAndReturnsNull() {
        JutsuDriftDetector detector = new JutsuDriftDetector();

        JutsuAnimeInfo info = new JutsuAnimeInfoParser(lenient(detector)).parse("", "x");

        assertThat(info).isNull();
        assertThat(detector.snapshot().recentEvents())
                .extracting(e -> e.signal())
                .containsExactly(JutsuDriftSignal.EMPTY_RESPONSE);
    }

    @Test
    void blankSlugObservesSchemaViolation() {
        JutsuDriftDetector detector = new JutsuDriftDetector();

        JutsuAnimeInfo info =
                new JutsuAnimeInfoParser(lenient(detector)).parse("<html><h1>X</h1></html>", " ");

        assertThat(info).isNull();
        assertThat(detector.snapshot().recentEvents())
                .extracting(e -> e.signal())
                .containsExactly(JutsuDriftSignal.SCHEMA_VIOLATION);
    }

    @Test
    void htmlWithoutH1ObservesSelectorMissAndReturnsNull() {
        JutsuDriftDetector detector = new JutsuDriftDetector();

        JutsuAnimeInfo info =
                new JutsuAnimeInfoParser(lenient(detector))
                        .parse("<html><body><p>nothing here</p></body></html>", "x");

        assertThat(info).isNull();
        assertThat(detector.snapshot().recentEvents())
                .extracting(e -> e.signal())
                .contains(JutsuDriftSignal.SELECTOR_MISS);
    }

    @Test
    void singleSeasonAnimeCollapsesIntoSeason1() {
        String html =
                "<html><head><meta property='og:image'"
                        + " content='x.jpg'></head><body><h1>Single</h1><a"
                        + " href='/single/episode-1.html' class='short-btn green video the_hildi'>1"
                        + " серия</a><a href='/single/episode-2.html' class='short-btn green video"
                        + " the_hildi'>2 серия</a></body></html>";
        JutsuDriftDetector detector = new JutsuDriftDetector();

        JutsuAnimeInfo info = new JutsuAnimeInfoParser(lenient(detector)).parse(html, "single");

        assertThat(info).isNotNull();
        assertThat(info.seasons()).hasSize(1);
        assertThat(info.seasons().get(0).index()).isEqualTo(1);
        assertThat(info.seasons().get(0).episodeCount()).isEqualTo(2);
    }

    @Test
    void crossPromoAnchorsAreSilentlyDropped() {
        // A cross-promo anchor ("watch this other anime"). The parser should NOT include it
        // and should NOT observe drift — cross-promo is expected.
        String html =
                "<html><body><h1>Single</h1><a href='/single/episode-1.html' class='short-btn green"
                    + " video the_hildi'>1 серия</a><a href='/anotheranime/season-2/episode-3.html'"
                    + " class='short-btn green video the_hildi'>related</a></body></html>";
        JutsuDriftDetector detector = new JutsuDriftDetector();

        JutsuAnimeInfo info = new JutsuAnimeInfoParser(lenient(detector)).parse(html, "single");

        assertThat(info).isNotNull();
        assertThat(info.totalEpisodeCount()).isEqualTo(1);
        assertThat(detector.snapshot().recentEvents()).isEmpty();
    }
}
