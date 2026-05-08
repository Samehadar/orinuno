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
    void labelledInfoBlockExposesGenresTypesYearsAndAgeRating() throws IOException {
        // The captured fixture's "Жанры/Темы/Годы выпуска/…/Возрастной рейтинг" block lists:
        //   Жанры: action, comedy, everyday, fantastic
        //   Темы: parody, superpower
        //   Годы выпуска: 2015, 2019, 2025
        //   Возрастной рейтинг: 18+
        String html = loadFixture("anime_info_onepunch.html");
        JutsuDriftDetector detector = new JutsuDriftDetector();

        JutsuAnimeInfo info =
                new JutsuAnimeInfoParser(lenient(detector)).parse(html, "onepuunchman");

        assertThat(info).isNotNull();
        assertThat(info.years())
                .as("multiple per-season air years from the labelled block")
                .containsExactly(2015, 2019, 2025);
        assertThat(info.ageRating())
                .as("18+ rating decoded from age_rating_18 class")
                .contains(JutsuAgeRating.RATING_18);
        assertThat(info.genres())
                .extracting(g -> g.slug())
                .containsExactlyInAnyOrder("action", "comedy", "everyday", "fantastic");
        assertThat(info.types())
                .extracting(t -> t.slug())
                .containsExactlyInAnyOrder("parody", "superpower");
    }

    @Test
    void labelledBlockHandlesAnonymousQueueButtonPrefixingTheGenresRow() {
        // jut.su renders an "Add to queue" CTA inline (no <br>) before the "Жанры:" row when
        // the viewer is anonymous. Earlier startsWith("Жанры:") logic missed the row in this
        // case and silently dropped genres. The parser now scans for label substrings anywhere
        // in the segment text. This is a small, targeted fixture instead of a full page capture.
        String html =
                "<html><head><meta property='og:image' content='x.jpg'></head><body><h1>X</h1><div"
                    + " class='under_video_additional'><a class='neon-btn'>Добавить в раздел «На"
                    + " очереди»</a> Жанры: <a href='/anime/action/'>боевик</a>, <a"
                    + " href='/anime/comedy/'>комедия</a>.<br>Темы: <a"
                    + " href='/anime/parody/'>пародия</a>.<br>Возрастной рейтинг: <span"
                    + " class='age_rating_all age_rating_16'>16<small>+</small></span></div><a"
                    + " href='/x/episode-1.html' class='short-btn green video the_hildi'>1</a>"
                    + "</body></html>";
        JutsuDriftDetector detector = new JutsuDriftDetector();

        JutsuAnimeInfo info = new JutsuAnimeInfoParser(lenient(detector)).parse(html, "x");

        assertThat(info).isNotNull();
        assertThat(info.genres())
                .as("genre slugs survive the anonymous-viewer CTA prefix on the same segment")
                .extracting(g -> g.slug())
                .containsExactlyInAnyOrder("action", "comedy");
        assertThat(info.types()).extracting(t -> t.slug()).containsExactlyInAnyOrder("parody");
        assertThat(info.ageRating()).contains(JutsuAgeRating.RATING_16);
    }

    @Test
    void missingLabelledBlockSurfacesSelectorMissAndFallsBackForCategories() {
        // No <div class="under_video_additional"> on the page — the parser should observe the
        // selector miss but still surface a valid info record with the page-wide /anime/{slug}/
        // sweep fallback feeding genres/types. Years and ageRating stay empty.
        String html =
                "<html><head><meta property='og:image' content='x.jpg'></head><body><h1>X</h1><a"
                        + " href='/anime/action/'>боевик</a><a href='/x/episode-1.html'"
                        + " class='short-btn green video the_hildi'>1</a></body></html>";
        JutsuDriftDetector detector = new JutsuDriftDetector();

        JutsuAnimeInfo info = new JutsuAnimeInfoParser(lenient(detector)).parse(html, "x");

        assertThat(info).isNotNull();
        assertThat(info.years()).isEmpty();
        assertThat(info.ageRating()).isEmpty();
        assertThat(info.genres()).extracting(g -> g.slug()).contains("action");
        assertThat(detector.snapshot().recentEvents())
                .extracting(e -> e.signal())
                .contains(JutsuDriftSignal.SELECTOR_MISS);
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
        // and should NOT observe drift on the cross-promo branch — cross-promo is expected.
        // The synthetic HTML below also lacks the labelled info block, which is a separate
        // SELECTOR_MISS the parser fires unconditionally; we filter it out here so the assertion
        // stays focused on the cross-promo invariant.
        String html =
                "<html><body><h1>Single</h1><a href='/single/episode-1.html' class='short-btn green"
                    + " video the_hildi'>1 серия</a><a href='/anotheranime/season-2/episode-3.html'"
                    + " class='short-btn green video the_hildi'>related</a></body></html>";
        JutsuDriftDetector detector = new JutsuDriftDetector();

        JutsuAnimeInfo info = new JutsuAnimeInfoParser(lenient(detector)).parse(html, "single");

        assertThat(info).isNotNull();
        assertThat(info.totalEpisodeCount()).isEqualTo(1);
        // Only the missing labelled block fires (expected for synthetic test HTML); no
        // additional drift from the cross-promo anchor itself.
        assertThat(detector.snapshot().recentEvents())
                .extracting(e -> e.detail())
                .allMatch(detail -> detail.contains("labelled block"));
    }

    @Test
    void filmsAreExtractedFromDedicatedFilmUrls() {
        // Mirrors the actual jut.su rendering for /life-no-game/: 12 episode anchors followed by
        // a "Полнометражные фильмы" <h2> and one or more film anchors at /{slug}/film-N.html.
        // The film anchors carry the same `short-btn video the_hildi` selector as episodes, so
        // discriminating by URL pattern (and not by selector colour or DOM position) is the only
        // way to keep films out of seasons and vice versa.
        String html =
                "<html><head><meta property='og:image' content='x.jpg'></head><body><h1>X</h1><a"
                    + " href='/life-no-game/episode-1.html' class='short-btn green video"
                    + " the_hildi'>1 серия</a><a href='/life-no-game/episode-2.html'"
                    + " class='short-btn green video the_hildi'>2 серия</a><h2 class='b-b-title"
                    + " the-anime-season center films_title'>Полнометражные фильмы</h2><a"
                    + " href='/life-no-game/film-1.html' class='short-btn black video the_hildi'>1"
                    + " фильм</a><a href='/life-no-game/film-2.html' class='short-btn black video"
                    + " the_hildi'>2 фильм</a></body></html>";
        JutsuDriftDetector detector = new JutsuDriftDetector();

        JutsuAnimeInfo info =
                new JutsuAnimeInfoParser(lenient(detector)).parse(html, "life-no-game");

        assertThat(info).isNotNull();
        assertThat(info.totalEpisodeCount())
                .as("films must NOT be counted as episodes")
                .isEqualTo(2);
        assertThat(info.totalFilmCount()).isEqualTo(2);
        assertThat(info.films())
                .extracting(JutsuFilmListing::index, JutsuFilmListing::url)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(1, "/life-no-game/film-1.html"),
                        org.assertj.core.groups.Tuple.tuple(2, "/life-no-game/film-2.html"));
        assertThat(info.films().get(0).label()).contains("1 фильм");
        // Neither films nor episodes should produce a drift signal beyond the missing labelled
        // info block (synthetic HTML).
        assertThat(detector.snapshot().recentEvents())
                .extracting(e -> e.detail())
                .allMatch(detail -> detail.contains("labelled block"));
    }

    @Test
    void unknownAnchorShapeFiresSchemaViolation() {
        // A future jut.su template that introduces, say, /{slug}/special-1.html under the same
        // selector should surface drift so we hear about it before users do. This test pins that
        // contract: anchors that match the selector but neither the episode nor the film URL
        // pattern emit SCHEMA_VIOLATION.
        String html =
                "<html><body><h1>Single</h1><a href='/single/episode-1.html' class='short-btn"
                        + " green video the_hildi'>1 серия</a><a href='/single/special-1.html'"
                        + " class='short-btn black video the_hildi'>спецвыпуск 1</a></body></html>";
        JutsuDriftDetector detector = new JutsuDriftDetector();

        JutsuAnimeInfo info = new JutsuAnimeInfoParser(lenient(detector)).parse(html, "single");

        assertThat(info).isNotNull();
        assertThat(info.totalEpisodeCount()).isEqualTo(1);
        assertThat(info.totalFilmCount()).isZero();
        assertThat(detector.snapshot().recentEvents())
                .extracting(e -> e.detail())
                .anyMatch(detail -> detail.contains("doesn't match episode/film pattern"));
    }

    @Test
    void crossPromoFilmAnchorsFromRelatedAnimeAreSilentlyDropped() {
        // jut.su's "related anime" cross-promos sometimes include film anchors from a different
        // slug. We must keep them out of the current entry's films list and not raise drift.
        String html =
                "<html><body><h1>Single</h1><a href='/single/episode-1.html' class='short-btn"
                        + " green video the_hildi'>1 серия</a><a href='/another/film-1.html'"
                        + " class='short-btn black video the_hildi'>чужой фильм</a></body></html>";
        JutsuDriftDetector detector = new JutsuDriftDetector();

        JutsuAnimeInfo info = new JutsuAnimeInfoParser(lenient(detector)).parse(html, "single");

        assertThat(info).isNotNull();
        assertThat(info.totalFilmCount()).isZero();
        // Only the labelled-block selector miss is allowed (synthetic test HTML).
        assertThat(detector.snapshot().recentEvents())
                .extracting(e -> e.detail())
                .allMatch(detail -> detail.contains("labelled block"));
    }
}
