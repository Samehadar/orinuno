package com.orinuno.cvh.parser;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.orinuno.cvh.model.RatingInfo;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

/**
 * Generic JSON-LD {@code aggregateRating} extractor. Host pages typically embed a single {@code
 * <script type="application/ld+json">} block describing the title; this parser pulls the rating
 * subnode out of either a bare object or a {@code @graph[0]} envelope.
 *
 * <p>Returns {@link Optional#empty()} on missing script, malformed JSON, or missing {@code
 * aggregateRating} — callers should treat it as "no rating available", not an error.
 */
@Slf4j
public final class JsonLdRatingParser {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private JsonLdRatingParser() {}

    public static Optional<RatingInfo> parse(Document doc) {
        Element script = doc.selectFirst("script[type=application/ld+json]");
        if (script == null) {
            return Optional.empty();
        }
        try {
            JsonNode root = OBJECT_MAPPER.readTree(script.html());
            JsonNode item = root.path("@graph").isArray() ? root.path("@graph").get(0) : root;
            if (item == null || item.isMissingNode()) {
                return Optional.empty();
            }
            JsonNode rating = item.path("aggregateRating");
            if (rating.isMissingNode()) {
                return Optional.empty();
            }
            return Optional.of(
                    new RatingInfo(
                            textOrNull(rating.path("ratingValue")),
                            textOrNull(rating.path("ratingCount")),
                            textOrNull(item.path("@type")),
                            textOrNull(item.path("datePublished"))));
        } catch (Exception ex) {
            log.debug("JSON-LD rating parse failed: {}", ex.toString());
            return Optional.empty();
        }
    }

    private static String textOrNull(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        String v = node.asText("").trim();
        return v.isEmpty() ? null : v;
    }
}
