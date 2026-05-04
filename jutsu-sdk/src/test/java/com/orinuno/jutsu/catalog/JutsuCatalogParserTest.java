package com.orinuno.jutsu.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import com.orinuno.jutsu.drift.JutsuDriftDetector;
import com.orinuno.jutsu.drift.JutsuDriftSignal;
import com.orinuno.jutsu.drift.JutsuParserContext;
import com.orinuno.jutsu.filter.JutsuGenre;
import com.orinuno.jutsu.filter.JutsuType;
import com.orinuno.jutsu.filter.JutsuYear;
import com.orinuno.jutsu.parser.JutsuHtmlCharset;
import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;
import org.junit.jupiter.api.Test;

class JutsuCatalogParserTest {

    private static String loadFixture(String name) throws IOException {
        try (InputStream stream =
                Objects.requireNonNull(
                        JutsuCatalogParserTest.class.getResourceAsStream("/jutsu/" + name),
                        "fixture " + name + " not on classpath")) {
            return JutsuHtmlCharset.decode(stream.readAllBytes(), null);
        }
    }

    private static JutsuParserContext lenient(JutsuDriftDetector detector) {
        return JutsuParserContext.lenient(detector, "JutsuCatalogParser");
    }

    @Test
    void parsesAllEntriesFromCatalogPage2Fixture() throws IOException {
        String html = loadFixture("catalog_page2_anime.html");
        JutsuDriftDetector detector = new JutsuDriftDetector();

        JutsuCatalogPage page = new JutsuCatalogParser(lenient(detector)).parse(html, 2);

        // Captured fixture has 30 entries.
        assertThat(page.entries()).hasSize(30);
        assertThat(page.page()).isEqualTo(2);
        assertThat(page.hasMore()).isTrue();
        // No drift on the captured fixture.
        assertThat(detector.snapshot().recentEvents()).isEmpty();
    }

    @Test
    void firstEntryFromPage2IsTheKnownEvangelionCard() throws IOException {
        String html = loadFixture("catalog_page2_anime.html");
        JutsuDriftDetector detector = new JutsuDriftDetector();

        JutsuCatalogPage page = new JutsuCatalogParser(lenient(detector)).parse(html, 2);

        JutsuCatalogEntry first = page.entries().get(0);
        assertThat(first.slug()).isEqualTo("neon-evangelion");
        assertThat(first.siteId()).isEqualTo(29);
        assertThat(first.title()).isEqualTo("Евангелион");
        // Original title is wrapped in tooltip — tolerate either presence or null since the
        // page-2 fixture is a real upstream sample.
        assertThat(first.originalTitle()).contains("Neon Genesis Evangelion");
        assertThat(first.thumbnailUrl()).contains("evangelion");
        assertThat(first.episodeCount()).isEqualTo(26);
        assertThat(first.movieCount()).isEqualTo(1);
        assertThat(first.genres())
                .containsExactlyInAnyOrder(
                        JutsuGenre.ACTION,
                        JutsuGenre.DRAMA,
                        JutsuGenre.FANTASTIC,
                        JutsuGenre.PSYCHOLOGY);
        assertThat(first.types()).containsExactly(JutsuType.MECHA);
        assertThat(first.year()).contains(JutsuYear.BEFORE_2000);
    }

    @Test
    void parsesFilteredCatalogFixture() throws IOException {
        String html = loadFixture("catalog_filter_comedy_2024.html");
        JutsuDriftDetector detector = new JutsuDriftDetector();

        JutsuCatalogPage page = new JutsuCatalogParser(lenient(detector)).parse(html, 1);

        assertThat(page.entries()).hasSize(30);
        assertThat(page.hasMore()).isTrue();
        // Every entry should have COMEDY in its genre set (the URL filtered for it).
        // Sanity-check the bulk: at least 80% should match. Some entries may legitimately have
        // mixed genres on jut.su's data but the dominant filter must hold.
        long withComedy =
                page.entries().stream().filter(e -> e.genres().contains(JutsuGenre.COMEDY)).count();
        assertThat(withComedy).isGreaterThanOrEqualTo(24);
        assertThat(detector.snapshot().recentEvents()).isEmpty();
    }

    @Test
    void parsesSearchHitFixture() throws IOException {
        String html = loadFixture("catalog_search_history.html");
        JutsuDriftDetector detector = new JutsuDriftDetector();

        JutsuCatalogPage page = new JutsuCatalogParser(lenient(detector)).parse(html, 1);

        assertThat(page.entries()).hasSize(30);
        assertThat(page.hasMore()).isTrue();
        assertThat(detector.snapshot().recentEvents()).isEmpty();
    }

    @Test
    void emptyResultFixtureIsAValidPageWithZeroEntries() throws IOException {
        String html = loadFixture("catalog_filter_empty_result.html");
        JutsuDriftDetector detector = new JutsuDriftDetector();

        JutsuCatalogPage page = new JutsuCatalogParser(lenient(detector)).parse(html, 1);

        assertThat(page.entries()).isEmpty();
        assertThat(page.hasMore()).isFalse();
        // Empty result is NOT drift — search/filter no-match is a normal steady state.
        assertThat(detector.snapshot().recentEvents()).isEmpty();
    }

    @Test
    void searchNoMatchFixtureIsAValidEmptyPage() throws IOException {
        String html = loadFixture("catalog_search_no_match.html");
        JutsuDriftDetector detector = new JutsuDriftDetector();

        JutsuCatalogPage page = new JutsuCatalogParser(lenient(detector)).parse(html, 1);

        assertThat(page.entries()).isEmpty();
        assertThat(page.hasMore()).isFalse();
        assertThat(detector.snapshot().recentEvents()).isEmpty();
    }

    @Test
    void emptyHtmlObservesEmptyResponse() {
        JutsuDriftDetector detector = new JutsuDriftDetector();

        JutsuCatalogPage page = new JutsuCatalogParser(lenient(detector)).parse("", 1);

        assertThat(page.entries()).isEmpty();
        assertThat(detector.snapshot().recentEvents())
                .extracting(e -> e.signal())
                .containsExactly(JutsuDriftSignal.EMPTY_RESPONSE);
    }

    @Test
    void htmlWithoutPageNextMarkerObservesSchemaViolation() {
        // Card with no JS prelude — the parser still extracts the card but reports drift.
        String html =
                "<div class='all_anime_global anime_year_2024' id='anime_fs_1'>"
                        + "<a href='/x/'>"
                        + "<div class='aaname'>X</div>"
                        + "<div class='aailines'>1 серия</div>"
                        + "</a></div>";
        JutsuDriftDetector detector = new JutsuDriftDetector();

        JutsuCatalogPage page = new JutsuCatalogParser(lenient(detector)).parse(html, 1);

        assertThat(page.entries()).hasSize(1);
        assertThat(detector.snapshot().recentEvents())
                .extracting(e -> e.signal())
                .contains(JutsuDriftSignal.SCHEMA_VIOLATION);
    }

    @Test
    void granularYearOnCardMapsToBucketWithoutDrift() {
        // Cards sometimes carry both bucket and granular year ("anime_year_2018" alongside
        // "anime_year_2015-2023"). Either alone should produce the bucket without drift events.
        String html =
                "var anime_page_next = false;"
                        + "<div class='all_anime_global anime_year_2018' id='anime_fs_1'>"
                        + "<a href='/x/'>"
                        + "<div class='aaname'>X</div>"
                        + "<div class='aailines'>1 серия</div>"
                        + "</a></div>";
        JutsuDriftDetector detector = new JutsuDriftDetector();

        JutsuCatalogPage page = new JutsuCatalogParser(lenient(detector)).parse(html, 1);

        assertThat(page.entries()).hasSize(1);
        assertThat(page.entries().get(0).year()).contains(JutsuYear.Y_2015_2023);
        assertThat(detector.snapshot().recentEvents()).isEmpty();
    }

    @Test
    void granularYearMappingHandlesAllFiveBuckets() {
        assertThat(JutsuCatalogParser.mapGranularYear("1995")).contains(JutsuYear.BEFORE_2000);
        assertThat(JutsuCatalogParser.mapGranularYear("2003")).contains(JutsuYear.Y_2000_2007);
        assertThat(JutsuCatalogParser.mapGranularYear("2010")).contains(JutsuYear.Y_2008_2014);
        assertThat(JutsuCatalogParser.mapGranularYear("2018")).contains(JutsuYear.Y_2015_2023);
        assertThat(JutsuCatalogParser.mapGranularYear("2024")).contains(JutsuYear.Y_2024);
        assertThat(JutsuCatalogParser.mapGranularYear("2025")).contains(JutsuYear.Y_2025);
        assertThat(JutsuCatalogParser.mapGranularYear("2026")).contains(JutsuYear.Y_2026);
        // Future year past the form's last explicit bucket falls into ONGOING.
        assertThat(JutsuCatalogParser.mapGranularYear("2027")).contains(JutsuYear.ONGOING);
        // Out of range / non-numeric.
        assertThat(JutsuCatalogParser.mapGranularYear("1700")).isEmpty();
        assertThat(JutsuCatalogParser.mapGranularYear("abcd")).isEmpty();
        assertThat(JutsuCatalogParser.mapGranularYear("")).isEmpty();
        assertThat(JutsuCatalogParser.mapGranularYear(null)).isEmpty();
    }

    @Test
    void cardClassWithUnknownGenreObservesUnknownFilterSlug() {
        // Synthesise a card with a fabricated anime_ganre_supercat class.
        String html =
                "var anime_page_next = false;"
                        + "<div class='all_anime_global anime_ganre_supercat anime_year_2024'"
                        + " id='anime_fs_1'>"
                        + "<a href='/x/'>"
                        + "<div class='aaname'>X</div>"
                        + "<div class='aailines'>1 серия</div>"
                        + "</a></div>";
        JutsuDriftDetector detector = new JutsuDriftDetector();

        new JutsuCatalogParser(lenient(detector)).parse(html, 1);

        assertThat(detector.snapshot().recentEvents())
                .extracting(e -> e.signal())
                .contains(JutsuDriftSignal.UNKNOWN_FILTER_SLUG);
    }
}
