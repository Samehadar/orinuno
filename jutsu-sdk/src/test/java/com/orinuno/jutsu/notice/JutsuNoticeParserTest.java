package com.orinuno.jutsu.notice;

import static org.assertj.core.api.Assertions.assertThat;

import com.orinuno.jutsu.drift.JutsuDriftDetector;
import com.orinuno.jutsu.drift.JutsuDriftSignal;
import com.orinuno.jutsu.drift.JutsuParserContext;
import com.orinuno.jutsu.parser.JutsuHtmlCharset;
import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;
import org.junit.jupiter.api.Test;

class JutsuNoticeParserTest {

    private static String loadFixture(String name) throws IOException {
        try (InputStream stream =
                Objects.requireNonNull(
                        JutsuNoticeParserTest.class.getResourceAsStream("/jutsu/" + name),
                        "fixture " + name + " not on classpath")) {
            return JutsuHtmlCharset.decode(stream.readAllBytes(), null);
        }
    }

    private static JutsuParserContext lenient(JutsuDriftDetector detector) {
        return JutsuParserContext.lenient(detector, "JutsuNoticeParser");
    }

    @Test
    void parsesFiftyEntriesFromCapturedFeedFixture() throws IOException {
        String html = loadFixture("notice_feed_18729.html");
        JutsuDriftDetector detector = new JutsuDriftDetector();

        JutsuNoticeFeed feed = new JutsuNoticeParser(lenient(detector)).parse(html, 18729);

        assertThat(feed.requestedCursor()).isEqualTo(18729);
        assertThat(feed.entries()).hasSize(50);
        assertThat(feed.hasEntries()).isTrue();
        assertThat(feed.nextCursor()).hasValue(18729 - 50);
    }

    @Test
    void zeroDriftEventsForCanonicalFeedFixture() throws IOException {
        String html = loadFixture("notice_feed_18729.html");
        JutsuDriftDetector detector = new JutsuDriftDetector();

        new JutsuNoticeParser(lenient(detector)).parse(html, 18729);

        assertThat(detector.snapshot().recentEvents()).isEmpty();
    }

    @Test
    void firstEntryHasExpectedShape() throws IOException {
        String html = loadFixture("notice_feed_18729.html");
        JutsuDriftDetector detector = new JutsuDriftDetector();

        JutsuNoticeFeed feed = new JutsuNoticeParser(lenient(detector)).parse(html, 18729);
        JutsuNoticeEntry first = feed.entries().get(0);

        assertThat(first.slug()).isEqualTo("shokugyou-kanteishi");
        assertThat(first.season()).isEqualTo(1);
        assertThat(first.episode()).isEqualTo(6);
        assertThat(first.title()).contains("временный инспектор").contains("6 серия");
        assertThat(first.episodeUrl())
                .isEqualTo("https://jut.su/shokugyou-kanteishi/episode-6.html");
        assertThat(first.thumbnailUrl()).isNotNull().contains("anime_35989");
        assertThat(first.relativeDate()).isEqualTo("сегодня ночью");
    }

    @Test
    void emptyHtmlIsTreatedAsHistoryBoundWithoutDrift() {
        JutsuDriftDetector detector = new JutsuDriftDetector();

        JutsuNoticeFeed feed = new JutsuNoticeParser(lenient(detector)).parse("", 0);

        assertThat(feed.entries()).isEmpty();
        assertThat(feed.hasEntries()).isFalse();
        assertThat(detector.snapshot().recentEvents())
                .as("empty body is the legitimate history-bound signal")
                .isEmpty();
    }

    @Test
    void nonEmptyBodyWithoutNoticeBlocksFiresSelectorMiss() {
        JutsuDriftDetector detector = new JutsuDriftDetector();

        JutsuNoticeFeed feed =
                new JutsuNoticeParser(lenient(detector)).parse("<p>nothing useful</p>", 100);

        assertThat(feed.entries()).isEmpty();
        assertThat(detector.snapshot().recentEvents())
                .extracting(e -> e.signal())
                .containsExactly(JutsuDriftSignal.SELECTOR_MISS);
    }

    @Test
    void entryWithBrokenUrlObservesSchemaViolationAndIsSkipped() {
        String html =
                "<div class='notice_cont'>"
                        + "<a class='notice_img' href='/foo/'><img src='thumb.jpg'/></a>"
                        + "<a class='notice_title2_2' href='/not-an-episode'>Bad</a>"
                        + "<div class='notice_date2'>сегодня</div>"
                        + "</div>"
                        + "<div class='notice_cont'>"
                        + "<a class='notice_img' href='/x/'><img src='ok.jpg'/></a>"
                        + "<a class='notice_title2_2' href='/x/episode-2.html'>OK: 2 серия</a>"
                        + "<div class='notice_date2'>вчера</div>"
                        + "</div>";
        JutsuDriftDetector detector = new JutsuDriftDetector();

        JutsuNoticeFeed feed = new JutsuNoticeParser(lenient(detector)).parse(html, 5);

        assertThat(feed.entries()).hasSize(1);
        assertThat(feed.entries().get(0).slug()).isEqualTo("x");
        assertThat(detector.snapshot().recentEvents())
                .extracting(e -> e.signal())
                .contains(JutsuDriftSignal.SCHEMA_VIOLATION);
    }

    @Test
    void entryMissingDateFiresSelectorMissAndIsSkipped() {
        String html =
                "<div class='notice_cont'>"
                        + "<a class='notice_img' href='/x/'><img src='ok.jpg'/></a>"
                        + "<a class='notice_title2_2' href='/x/episode-2.html'>OK: 2 серия</a>"
                        + "</div>";
        JutsuDriftDetector detector = new JutsuDriftDetector();

        JutsuNoticeFeed feed = new JutsuNoticeParser(lenient(detector)).parse(html, 5);

        assertThat(feed.entries()).isEmpty();
        assertThat(detector.snapshot().recentEvents())
                .extracting(e -> e.signal())
                .contains(JutsuDriftSignal.SELECTOR_MISS);
    }

    @Test
    void entryWithoutThumbnailStillProducesEntry() {
        String html =
                "<div class='notice_cont'>"
                        + "<a class='notice_title2_2' href='/x/episode-2.html'>OK: 2 серия</a>"
                        + "<div class='notice_date2'>вчера</div>"
                        + "</div>";
        JutsuDriftDetector detector = new JutsuDriftDetector();

        JutsuNoticeFeed feed = new JutsuNoticeParser(lenient(detector)).parse(html, 5);

        assertThat(feed.entries()).hasSize(1);
        assertThat(feed.entries().get(0).thumbnailUrl()).isNull();
        assertThat(detector.snapshot().recentEvents()).isEmpty();
    }
}
