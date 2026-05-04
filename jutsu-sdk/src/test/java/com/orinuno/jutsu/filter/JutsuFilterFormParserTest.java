package com.orinuno.jutsu.filter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.orinuno.jutsu.drift.JutsuDriftDetector;
import com.orinuno.jutsu.drift.JutsuDriftException;
import com.orinuno.jutsu.drift.JutsuDriftSignal;
import com.orinuno.jutsu.drift.JutsuParserContext;
import com.orinuno.jutsu.parser.JutsuHtmlCharset;
import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;
import org.junit.jupiter.api.Test;

class JutsuFilterFormParserTest {

    private static String loadFixture(String name) throws IOException {
        try (InputStream stream =
                Objects.requireNonNull(
                        JutsuFilterFormParserTest.class.getResourceAsStream("/jutsu/" + name),
                        "fixture " + name + " not on classpath")) {
            return JutsuHtmlCharset.decode(stream.readAllBytes(), null);
        }
    }

    @Test
    void parsesAllFourBlocksFromCapturedFixture() throws IOException {
        String html = loadFixture("anime_filter_form.html");
        JutsuDriftDetector detector = new JutsuDriftDetector();
        JutsuParserContext ctx = JutsuParserContext.lenient(detector, "JutsuFilterFormParser");

        JutsuFilterFormParser.JutsuFilterFormSnapshot snap =
                new JutsuFilterFormParser(ctx).parse(html);

        assertThat(snap.genres()).hasSize(JutsuGenre.values().length);
        assertThat(snap.types()).hasSize(JutsuType.values().length);
        assertThat(snap.years()).hasSize(JutsuYear.values().length);
        // Order block has the elided default + 4 explicit entries.
        assertThat(snap.orders()).hasSize(JutsuSort.values().length);

        // Compare slugs to the enums (declaration order).
        assertThat(snap.genres().stream().map(JutsuFilterFormParser.FilterEntry::slug))
                .containsExactlyElementsOf(
                        java.util.Arrays.stream(JutsuGenre.values())
                                .map(JutsuGenre::slug)
                                .toList());
        assertThat(snap.types().stream().map(JutsuFilterFormParser.FilterEntry::slug))
                .containsExactlyElementsOf(
                        java.util.Arrays.stream(JutsuType.values()).map(JutsuType::slug).toList());
        assertThat(snap.years().stream().map(JutsuFilterFormParser.FilterEntry::slug))
                .containsExactlyElementsOf(
                        java.util.Arrays.stream(JutsuYear.values()).map(JutsuYear::slug).toList());
        assertThat(snap.orders().stream().map(JutsuFilterFormParser.FilterEntry::slug))
                .containsExactlyElementsOf(
                        java.util.Arrays.stream(JutsuSort.values()).map(JutsuSort::slug).toList());
    }

    @Test
    void capturedFixtureProducesZeroDriftEvents() throws IOException {
        String html = loadFixture("anime_filter_form.html");
        JutsuDriftDetector detector = new JutsuDriftDetector();
        JutsuParserContext ctx = JutsuParserContext.lenient(detector, "JutsuFilterFormParser");

        new JutsuFilterFormParser(ctx).parse(html);

        // The fixture is the source of truth for the enums, so the parser must not observe
        // anything against it. Once an enum is added or removed, this test will need updating
        // alongside the enum declaration.
        assertThat(detector.snapshot().recentEvents())
                .as("captured fixture must round-trip through the enums without drift")
                .isEmpty();
    }

    @Test
    void labelsRoundTripFromFixtureToEnumLabels() throws IOException {
        String html = loadFixture("anime_filter_form.html");
        JutsuDriftDetector detector = new JutsuDriftDetector();
        JutsuParserContext ctx = JutsuParserContext.lenient(detector, "JutsuFilterFormParser");

        JutsuFilterFormParser.JutsuFilterFormSnapshot snap =
                new JutsuFilterFormParser(ctx).parse(html);

        for (JutsuFilterFormParser.FilterEntry entry : snap.genres()) {
            JutsuGenre matched = JutsuGenre.fromSlug(entry.slug()).orElseThrow();
            assertThat(matched.label()).isEqualTo(entry.label());
        }
        for (JutsuFilterFormParser.FilterEntry entry : snap.types()) {
            JutsuType matched = JutsuType.fromSlug(entry.slug()).orElseThrow();
            assertThat(matched.label()).isEqualTo(entry.label());
        }
        for (JutsuFilterFormParser.FilterEntry entry : snap.years()) {
            JutsuYear matched = JutsuYear.fromSlug(entry.slug()).orElseThrow();
            assertThat(matched.label()).isEqualTo(entry.label());
        }
        for (JutsuFilterFormParser.FilterEntry entry : snap.orders()) {
            if (entry.slug().isEmpty()) {
                // BY_RATING — label match by direct lookup.
                assertThat(JutsuSort.BY_RATING.label()).isEqualTo(entry.label());
            } else {
                JutsuSort matched = JutsuSort.fromSlug(entry.slug()).orElseThrow();
                assertThat(matched.label()).isEqualTo(entry.label());
            }
        }
    }

    @Test
    void emptyHtmlObservesEmptyResponseAndReturnsEmptySnapshot() {
        JutsuDriftDetector detector = new JutsuDriftDetector();
        JutsuParserContext ctx = JutsuParserContext.lenient(detector, "JutsuFilterFormParser");

        JutsuFilterFormParser.JutsuFilterFormSnapshot snap =
                new JutsuFilterFormParser(ctx).parse("");

        assertThat(snap.genres()).isEmpty();
        assertThat(snap.types()).isEmpty();
        assertThat(snap.years()).isEmpty();
        assertThat(snap.orders()).isEmpty();
        assertThat(detector.snapshot().recentEvents())
                .extracting(e -> e.signal())
                .containsExactly(JutsuDriftSignal.EMPTY_RESPONSE);
    }

    @Test
    void missingFilterBlocksAreReportedAsSelectorMisses() {
        String htmlWithoutForm = "<html><body><h1>Maintenance</h1></body></html>";
        JutsuDriftDetector detector = new JutsuDriftDetector();
        JutsuParserContext ctx = JutsuParserContext.lenient(detector, "JutsuFilterFormParser");

        new JutsuFilterFormParser(ctx).parse(htmlWithoutForm);

        // Every block selector miss is reported as a separate event — the operator should see
        // the FULL list of missing selectors so we know whether the page changed entirely or
        // only one block.
        assertThat(detector.snapshot().recentEvents())
                .extracting(e -> e.signal())
                .containsOnly(JutsuDriftSignal.SELECTOR_MISS);
        assertThat(detector.snapshot().recentEvents()).hasSize(4);
    }

    @Test
    void strictModeOnMaintenancePageThrowsOnFirstMiss() {
        String htmlWithoutForm = "<html><body><h1>Maintenance</h1></body></html>";
        JutsuDriftDetector detector = new JutsuDriftDetector();
        JutsuParserContext ctx =
                JutsuParserContext.strict(detector, "JutsuFilterFormParser", "stripped.html");

        assertThatExceptionOfType(JutsuDriftException.class)
                .isThrownBy(() -> new JutsuFilterFormParser(ctx).parse(htmlWithoutForm))
                .satisfies(
                        ex ->
                                assertThat(ex.event().signal())
                                        .isEqualTo(JutsuDriftSignal.SELECTOR_MISS));
    }
}
