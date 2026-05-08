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

        JutsuPageMeta page =
                new JutsuEpisodePageParser(lenient(detector))
                        .parse(html, "/onepuunchman/season-1/episode-1.html");

        assertThat(page).isInstanceOf(JutsuEpisodeMeta.class);
        JutsuEpisodeMeta meta = (JutsuEpisodeMeta) page;
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

        JutsuPageMeta meta = new JutsuEpisodePageParser(lenient(detector)).parse("", "/x/");

        assertThat(meta).isNull();
        assertThat(detector.snapshot().recentEvents())
                .extracting(e -> e.signal())
                .containsExactly(JutsuDriftSignal.EMPTY_RESPONSE);
    }

    @Test
    void htmlWithoutTitleObservesSelectorMissAndReturnsNull() {
        String html = "<html><body><h1>x</h1></body></html>";
        JutsuDriftDetector detector = new JutsuDriftDetector();

        JutsuPageMeta meta =
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

        JutsuPageMeta meta =
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

        JutsuPageMeta page =
                new JutsuEpisodePageParser(lenient(detector))
                        .parse(html, "/bar/season-1/episode-1.html");

        // Parse still succeeds against the canonical, but drift is observed.
        assertThat(page).isInstanceOf(JutsuEpisodeMeta.class);
        assertThat(((JutsuEpisodeMeta) page).slug()).isEqualTo("foo");
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

        JutsuPageMeta page =
                new JutsuEpisodePageParser(lenient(detector)).parse(html, "/single/episode-3.html");

        assertThat(page).isInstanceOf(JutsuEpisodeMeta.class);
        JutsuEpisodeMeta meta = (JutsuEpisodeMeta) page;
        assertThat(meta.slug()).isEqualTo("single");
        assertThat(meta.season()).isEqualTo(1);
        assertThat(meta.episode()).isEqualTo(3);
        assertThat(meta.premiumGated()).isFalse();
    }

    @Test
    void filmCanonicalYieldsJutsuFilmMeta() {
        String html =
                "<html><head><title>Смотреть Нет игры - нет жизни 1 фильм на Jut.su</title>"
                        + "<link rel='canonical' href='https://jut.su/life-no-game/film-1.html'/>"
                        + "<meta property='og:image' content='https://gen.jut.su/preview/1.jpg'/>"
                        + "</head><body><h1>Смотреть 1 фильм Нет игры - нет жизни</h1>"
                        + "<a class='vncenter' href='/life-no-game/'>Все серии</a>"
                        + "<div class='tab_need_plus'>Jutsu+</div>"
                        + "</body></html>";
        JutsuDriftDetector detector = new JutsuDriftDetector();

        JutsuPageMeta page =
                new JutsuEpisodePageParser(lenient(detector))
                        .parse(html, "/life-no-game/film-1.html");

        assertThat(page).isInstanceOf(JutsuFilmMeta.class);
        JutsuFilmMeta film = (JutsuFilmMeta) page;
        assertThat(film.slug()).isEqualTo("life-no-game");
        assertThat(film.filmIndex()).isEqualTo(1);
        assertThat(film.displayTitle()).contains("1 фильм");
        assertThat(film.pageTitle()).contains("Jut.su");
        assertThat(film.canonicalUrl()).isEqualTo("https://jut.su/life-no-game/film-1.html");
        assertThat(film.thumbnailUrl()).isEqualTo("https://gen.jut.su/preview/1.jpg");
        // Single film: no prev / next siblings on this anime.
        assertThat(film.hasPrev()).isFalse();
        assertThat(film.hasNext()).isFalse();
        assertThat(film.allEpisodesUrl()).isEqualTo("/life-no-game/");
        assertThat(film.premiumGated()).isTrue();
        assertThat(detector.snapshot().recentEvents())
                .as("clean film page must not fire any drift signals")
                .isEmpty();
    }

    @Test
    void filmKindFlipBetweenExpectedAndCanonicalObservesSchemaViolation() {
        String html =
                "<html><head><title>t</title>"
                        + "<link rel='canonical' href='https://jut.su/life-no-game/film-1.html'/>"
                        + "</head><body><h1>x</h1></body></html>";
        JutsuDriftDetector detector = new JutsuDriftDetector();

        JutsuPageMeta page =
                new JutsuEpisodePageParser(lenient(detector))
                        .parse(html, "/life-no-game/episode-1.html");

        assertThat(page).isInstanceOf(JutsuFilmMeta.class);
        assertThat(detector.snapshot().recentEvents())
                .extracting(e -> e.signal())
                .contains(JutsuDriftSignal.SCHEMA_VIOLATION);
    }

    @Test
    void unrecognisedCanonicalShapeObservesSchemaViolationAndReturnsNull() {
        String html =
                "<html><head><title>t</title>"
                        + "<link rel='canonical' href='https://jut.su/life-no-game/special.html'/>"
                        + "</head><body><h1>x</h1></body></html>";
        JutsuDriftDetector detector = new JutsuDriftDetector();

        JutsuPageMeta meta =
                new JutsuEpisodePageParser(lenient(detector))
                        .parse(html, "/life-no-game/special.html");

        assertThat(meta).isNull();
        assertThat(detector.snapshot().recentEvents())
                .extracting(e -> e.signal())
                .contains(JutsuDriftSignal.SCHEMA_VIOLATION);
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
