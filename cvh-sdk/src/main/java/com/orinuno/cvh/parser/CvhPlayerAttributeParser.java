package com.orinuno.cvh.parser;

import jakarta.annotation.Nullable;
import java.util.Optional;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

/**
 * Host-agnostic helper for the {@code <video-player>} custom element CVH ships on every embedding
 * page. The element shape is identical across hosts — only the surrounding HTML differs — so this
 * extractor is reused by every {@link com.orinuno.cvh.host.CvhHostPageParser} implementation.
 */
public final class CvhPlayerAttributeParser {

    public static final String ATTR_TITLE_ID = "data-title-id";
    public static final String ATTR_PUBLISHER_ID = "data-publisher-id";
    public static final String ATTR_AGGREGATOR = "data-aggregator";
    public static final String ATTR_PRIORITY_VOICE = "priority-voice";

    private CvhPlayerAttributeParser() {}

    public static Optional<Element> findPlayer(Document doc) {
        return Optional.ofNullable(doc.selectFirst("video-player"));
    }

    @Nullable
    public static String attr(Document doc, String attrName) {
        return findPlayer(doc).map(el -> trimToNull(el.attr(attrName))).orElse(null);
    }

    public static String attrOrDefault(Document doc, String attrName, String defaultValue) {
        String v = attr(doc, attrName);
        return v != null ? v : defaultValue;
    }

    @Nullable
    static String trimToNull(@Nullable String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
