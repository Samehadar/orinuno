package com.orinuno.jutsu.drift;

import jakarta.annotation.Nullable;
import java.time.Instant;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

/**
 * Per-parse context bridging parsers and {@link JutsuDriftDetector}. Exposes a small API for the
 * three things every parser does with selectors:
 *
 * <ol>
 *   <li>Require a selector to match — call {@link #requireSelector(Element, String, String)}; in
 *       lenient mode missing selectors are observed and the call returns {@code null}; in strict
 *       mode they throw {@link JutsuDriftException}.
 *   <li>Take an optional selector — call {@link #optionalSelector(Element, String)}; never throws,
 *       never observes (matches the "this section may legitimately be absent" semantics).
 *   <li>Report a structured drift event — call {@link #observe(JutsuDriftSignal, String)}.
 * </ol>
 *
 * <p>Strict mode is for tests and the orinuno-app scheduled canary probe, where any deviation from
 * the captured baseline must surface as a hard error. Production callers use lenient mode so a
 * single new {@code <div>} class doesn't break the user-visible catalog page.
 *
 * <p>Construction is intentionally explicit (no default detector) — passing the wrong source label
 * silently spreads drift events across unrelated dashboards, so we surface this as a constructor
 * argument rather than a setter.
 */
public final class JutsuParserContext {

    private final JutsuDriftDetector detector;
    private final String source;
    private final boolean strict;
    @Nullable private final String fixtureRef;

    public JutsuParserContext(JutsuDriftDetector detector, String source, boolean strict) {
        this(detector, source, strict, null);
    }

    public JutsuParserContext(
            JutsuDriftDetector detector,
            String source,
            boolean strict,
            @Nullable String fixtureRef) {
        if (detector == null) throw new IllegalArgumentException("detector must not be null");
        if (source == null || source.isBlank()) {
            throw new IllegalArgumentException("source must not be blank");
        }
        this.detector = detector;
        this.source = source;
        this.strict = strict;
        this.fixtureRef = fixtureRef;
    }

    /** Production parser context: lenient mode, no fixture ref. */
    public static JutsuParserContext lenient(JutsuDriftDetector detector, String source) {
        return new JutsuParserContext(detector, source, false, null);
    }

    /** Test/canary context: strict mode. Pair with a fixture ref for diagnostic ergonomics. */
    public static JutsuParserContext strict(
            JutsuDriftDetector detector, String source, @Nullable String fixtureRef) {
        return new JutsuParserContext(detector, source, true, fixtureRef);
    }

    public boolean isStrict() {
        return strict;
    }

    public JutsuDriftDetector detector() {
        return detector;
    }

    public String source() {
        return source;
    }

    @Nullable
    public String fixtureRef() {
        return fixtureRef;
    }

    /**
     * Find the first descendant element matching {@code selector}. Reports {@link
     * JutsuDriftSignal#SELECTOR_MISS} when no match is found and either returns {@code null}
     * (lenient) or throws {@link JutsuDriftException} (strict).
     *
     * @param root jsoup root to search; never null
     * @param selector CSS selector; never null
     * @param diagnostic short human-readable detail used in the drift event message
     */
    @Nullable
    public Element requireSelector(Element root, String selector, String diagnostic) {
        if (root == null) throw new IllegalArgumentException("root must not be null");
        Element element = root.selectFirst(selector);
        if (element == null) {
            JutsuDriftEvent event =
                    new JutsuDriftEvent(
                            JutsuDriftSignal.SELECTOR_MISS,
                            source,
                            diagnostic,
                            Instant.now(),
                            selector,
                            fixtureRef);
            detector.observe(event);
            if (strict) {
                throw new JutsuDriftException(event);
            }
        }
        return element;
    }

    /**
     * Find all descendants matching {@code selector}. When the result is empty <em>and</em> the
     * caller declared it required (via {@code requireNonEmpty=true}), reports a {@link
     * JutsuDriftSignal#SELECTOR_MISS}. When {@code requireNonEmpty=false}, no event is observed
     * (the caller is signalling that empty is a valid steady state — e.g., empty-search-result
     * fixture).
     */
    public Elements requireSelectors(
            Element root, String selector, String diagnostic, boolean requireNonEmpty) {
        if (root == null) throw new IllegalArgumentException("root must not be null");
        Elements elements = root.select(selector);
        if (elements.isEmpty() && requireNonEmpty) {
            JutsuDriftEvent event =
                    new JutsuDriftEvent(
                            JutsuDriftSignal.SELECTOR_MISS,
                            source,
                            diagnostic,
                            Instant.now(),
                            selector,
                            fixtureRef);
            detector.observe(event);
            if (strict) {
                throw new JutsuDriftException(event);
            }
        }
        return elements;
    }

    /**
     * Find the first descendant matching {@code selector}, returning {@code null} on miss without
     * reporting drift. Used for legitimately-optional sections (e.g., the optional second-season
     * block on an anime info page).
     */
    @Nullable
    public Element optionalSelector(Element root, String selector) {
        if (root == null) throw new IllegalArgumentException("root must not be null");
        return root.selectFirst(selector);
    }

    /** Report an arbitrary drift signal. Strict mode promotes the event to a thrown exception. */
    public void observe(JutsuDriftSignal signal, String detail) {
        observe(signal, detail, null);
    }

    /** Report a drift signal with an explicit selector hint (typically for SELECTOR_MISS). */
    public void observe(JutsuDriftSignal signal, String detail, @Nullable String selector) {
        JutsuDriftEvent event =
                new JutsuDriftEvent(signal, source, detail, Instant.now(), selector, fixtureRef);
        detector.observe(event);
        if (strict) {
            throw new JutsuDriftException(event);
        }
    }
}
