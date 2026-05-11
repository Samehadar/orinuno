package com.orinuno.cvh.parser;

import jakarta.annotation.Nullable;
import java.net.URI;
import java.time.Instant;
import java.util.Optional;

/**
 * URL-shape helpers for CVH signed CDN links.
 *
 * <p>Signed URLs carry {@code expires=<unix-millis>}; that millisecond timestamp drives the cache
 * refresh decision. Every signed URL in one {@code /sv/video} response shares the same value so
 * extracting it from any of them is sufficient.
 */
public final class CvhUrlParser {

    private CvhUrlParser() {}

    public static Optional<Instant> parseExpiresFromUrl(@Nullable String signedUrl) {
        if (signedUrl == null || signedUrl.isBlank()) {
            return Optional.empty();
        }
        try {
            String query = new URI(signedUrl).getQuery();
            if (query == null) {
                return Optional.empty();
            }
            for (String part : query.split("&")) {
                if (part.startsWith("expires=")) {
                    String raw = part.substring("expires=".length());
                    if (raw.isEmpty()) {
                        return Optional.empty();
                    }
                    return Optional.of(Instant.ofEpochMilli(Long.parseLong(raw)));
                }
            }
            return Optional.empty();
        } catch (Exception ex) {
            return Optional.empty();
        }
    }

    public static String extractSlug(String pageUrl) {
        if (pageUrl == null || pageUrl.isBlank()) {
            return "";
        }
        String noQuery = pageUrl.split("\\?", 2)[0];
        if (noQuery.endsWith("/")) {
            noQuery = noQuery.substring(0, noQuery.length() - 1);
        }
        int slash = noQuery.lastIndexOf('/');
        return slash >= 0 ? noQuery.substring(slash + 1) : noQuery;
    }
}
