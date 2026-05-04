package com.orinuno.jutsu.drift;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class JutsuParserContextTest {

    private static final String HTML =
            "<div class='page'>"
                    + "<a class='hit' href='/foo/'>Foo</a>"
                    + "<span class='also-hit'>bar</span>"
                    + "</div>";

    private JutsuDriftDetector detector;
    private Document doc;

    @BeforeEach
    void setUp() {
        detector = new JutsuDriftDetector();
        doc = Jsoup.parse(HTML);
    }

    @Test
    void requireSelectorReturnsMatchingElement() {
        JutsuParserContext ctx = JutsuParserContext.lenient(detector, "test");

        Element hit = ctx.requireSelector(doc, "a.hit", "looking for the link");

        assertThat(hit).isNotNull();
        assertThat(hit.text()).isEqualTo("Foo");
        assertThat(detector.snapshot().eventsInWindow()).isZero();
    }

    @Test
    void requireSelectorObservesMissInLenientMode() {
        JutsuParserContext ctx = JutsuParserContext.lenient(detector, "JutsuCatalogParser");

        Element missing = ctx.requireSelector(doc, "a.does-not-exist", "no card found");

        assertThat(missing).isNull();
        JutsuDriftSnapshot snap = detector.snapshot();
        assertThat(snap.eventsInWindow()).isEqualTo(1);
        assertThat(snap.recentEvents().get(0).signal()).isEqualTo(JutsuDriftSignal.SELECTOR_MISS);
        assertThat(snap.recentEvents().get(0).source()).isEqualTo("JutsuCatalogParser");
        assertThat(snap.recentEvents().get(0).selector()).isEqualTo("a.does-not-exist");
        assertThat(snap.recentEvents().get(0).detail()).isEqualTo("no card found");
    }

    @Test
    void requireSelectorThrowsInStrictMode() {
        JutsuParserContext ctx =
                JutsuParserContext.strict(detector, "JutsuCatalogParser", "fixture-x.html");

        assertThatExceptionOfType(JutsuDriftException.class)
                .isThrownBy(() -> ctx.requireSelector(doc, "a.does-not-exist", "no card found"))
                .satisfies(
                        ex -> {
                            assertThat(ex.event().signal())
                                    .isEqualTo(JutsuDriftSignal.SELECTOR_MISS);
                            assertThat(ex.event().fixtureRef()).isEqualTo("fixture-x.html");
                        });
        // Strict mode still observes the event before throwing — operators see it on the dashboard.
        assertThat(detector.snapshot().eventsInWindow()).isEqualTo(1);
    }

    @Test
    void requireSelectorsAllowsEmptyWhenRequireNonEmptyFalse() {
        JutsuParserContext ctx = JutsuParserContext.lenient(detector, "test");

        assertThat(ctx.requireSelectors(doc, ".missing", "could be empty", false)).isEmpty();
        assertThat(detector.snapshot().eventsInWindow())
                .as("requireNonEmpty=false must not observe drift on empty result")
                .isZero();
    }

    @Test
    void requireSelectorsObservesEmptyWhenRequireNonEmptyTrue() {
        JutsuParserContext ctx = JutsuParserContext.lenient(detector, "test");

        assertThat(ctx.requireSelectors(doc, ".missing", "must have rows", true)).isEmpty();
        assertThat(detector.snapshot().eventsInWindow()).isEqualTo(1);
        assertThat(detector.snapshot().recentEvents().get(0).signal())
                .isEqualTo(JutsuDriftSignal.SELECTOR_MISS);
    }

    @Test
    void optionalSelectorReturnsNullWithoutObserving() {
        JutsuParserContext ctx = JutsuParserContext.lenient(detector, "test");

        assertThat(ctx.optionalSelector(doc, ".not-there")).isNull();
        assertThat(detector.snapshot().eventsInWindow())
                .as("optionalSelector miss must not observe drift")
                .isZero();
    }

    @Test
    void observeArbitrarySignal() {
        JutsuParserContext ctx = JutsuParserContext.lenient(detector, "JutsuFilterFormParser");

        ctx.observe(JutsuDriftSignal.UNKNOWN_FILTER_SLUG, "new-genre seen");

        JutsuDriftSnapshot snap = detector.snapshot();
        assertThat(snap.eventsInWindow()).isEqualTo(1);
        assertThat(snap.recentEvents().get(0).signal())
                .isEqualTo(JutsuDriftSignal.UNKNOWN_FILTER_SLUG);
        assertThat(snap.recentEvents().get(0).detail()).isEqualTo("new-genre seen");
    }

    @Test
    void observeInStrictModeThrows() {
        JutsuParserContext ctx = JutsuParserContext.strict(detector, "p", "f");

        assertThatExceptionOfType(JutsuDriftException.class)
                .isThrownBy(() -> ctx.observe(JutsuDriftSignal.NEW_CSS_CLASS, "novel class y"));
        assertThat(detector.snapshot().eventsInWindow()).isEqualTo(1);
    }

    @Test
    void invalidConstructorArgsThrow() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new JutsuParserContext(null, "src", false));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new JutsuParserContext(detector, null, false));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new JutsuParserContext(detector, "  ", false));
    }
}
