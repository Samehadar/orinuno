package com.orinuno.service;

import com.orinuno.service.metrics.KodikDecoderMetrics.UpstreamErrorClass;

/**
 * Maps a Kodik upstream error response body (Russian-language JSON / HTML) to a small enum that
 * dashboards and alerts can fan out on without unbounded cardinality. DECODE-7 (500-handling).
 *
 * <p>Reference: docs/quirks-and-hacks.md "Kodik returns HTTP 500 for application-level errors"
 * entry and ADR 0003.
 *
 * <p>The matching is substring-based and case-insensitive on the Russian phrase keys. We
 * intentionally do NOT regex over the full body — Kodik mixes JSON {@code {"error":"…"}} responses
 * (signed-params stale, missing token) with HTML pages (router-level rejections, generic 500s), and
 * we want consistent classification across both.
 */
public final class KodikUpstreamErrorClassifier {

    private KodikUpstreamErrorClassifier() {}

    public static UpstreamErrorClass classify(int httpStatus, String body) {
        if (body == null || body.isBlank()) {
            return UpstreamErrorClass.OTHER;
        }
        // Lower-case once; Russian text comparison is unambiguous here.
        String normalized = body.toLowerCase(java.util.Locale.ROOT);

        // Order matters: more-specific first.
        if (normalized.contains("отсутствует или неверный токен")) {
            // Same body is reused by Kodik for both:
            //   1) ?token= missing/invalid              → token-invalid
            //   2) /ftor with stale d_sign / pd_sign    → signed-params-stale (misleading)
            // Heuristic: /ftor (decoder) returns it on 500 only when signed-params are bad
            //            (token is in url-params, not query-string). REST endpoints return it
            //            on 500 when query token is bad. We can't disambiguate from body alone,
            //            so callers can pass `httpStatus` + their endpoint context if they need
            //            finer granularity. Default to TOKEN_INVALID since that's the Kodik
            //            literal meaning of the message.
            return UpstreamErrorClass.TOKEN_INVALID;
        }
        if (normalized.contains("не указан хотя бы один параметр")) {
            return UpstreamErrorClass.MISSING_SEARCH_PARAM;
        }
        if (normalized.contains("неправильный тип")) {
            return UpstreamErrorClass.WRONG_TYPE;
        }
        return UpstreamErrorClass.OTHER;
    }

    /**
     * Convenience: when a decoder POST to {@code /ftor} returns 500 with the "missing or invalid
     * token" body, the actual root cause is almost always stale signed iframe params (the iframe
     * URL was fetched too long ago and {@code d_sign} / {@code pd_sign} expired). Use this overload
     * from decoder-specific call sites to get the more accurate {@link
     * UpstreamErrorClass#SIGNED_PARAMS_STALE} class.
     */
    public static UpstreamErrorClass classifyForDecoder(int httpStatus, String body) {
        UpstreamErrorClass coarse = classify(httpStatus, body);
        return coarse == UpstreamErrorClass.TOKEN_INVALID
                ? UpstreamErrorClass.SIGNED_PARAMS_STALE
                : coarse;
    }
}
