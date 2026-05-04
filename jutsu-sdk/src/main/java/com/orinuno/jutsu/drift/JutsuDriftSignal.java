package com.orinuno.jutsu.drift;

/**
 * Signal types the {@link JutsuDriftDetector} understands. Every parser/client in the SDK reports
 * deviations from the captured baseline through one of these values, so downstream dashboards and
 * the {@code MultiSourceRanker} only have to learn this enum.
 *
 * <p>The enum is intentionally narrow: each value describes a single failure mode that can be acted
 * on independently (e.g., {@link #SELECTOR_MISS} → re-capture fixture, {@link #UNKNOWN_FILTER_SLUG}
 * → extend filter enum, {@link #UNEXPECTED_HTTP_STATUS} → check upstream health). Adding a new
 * signal is cheap; collapsing two signals later is expensive.
 */
public enum JutsuDriftSignal {

    /**
     * A CSS selector required by a parser returned zero matches against a real response. Strongest
     * indicator that the page DOM changed under us.
     */
    SELECTOR_MISS,

    /**
     * A {@code (slug, label)} pair appeared on the filter form that no entry in {@link
     * com.orinuno.jutsu.filter} maps to. Surfaces newly-added genres/types/years/sorts before they
     * cause silent gaps in catalog filtering.
     */
    UNKNOWN_FILTER_SLUG,

    /**
     * A class appeared on a known element that was not part of the captured manifest (e.g., a new
     * {@code anime_ganre_*} class on the filter wall). Less severe than {@link
     * #UNKNOWN_FILTER_SLUG} because the parser can still produce a result.
     */
    NEW_CSS_CLASS,

    /**
     * Upstream returned an HTTP status outside the expected set for that endpoint. The catalog
     * /info/notice endpoints all return 200 in steady state; 4xx/5xx signal upstream incident or
     * blocking.
     */
    UNEXPECTED_HTTP_STATUS,

    /**
     * Response body was below the expected lower bound for that endpoint. Useful to detect partial
     * AJAX responses, truncated bodies, or upstream gateway issues.
     */
    RESPONSE_TOO_SMALL,

    /**
     * Response advertised a charset other than {@code windows-1251} (jut.su's documented default).
     * Defence-in-depth — a charset switch would mojibake every cyrillic title and silently break
     * the {@code MultiSourceRanker} ranking.
     */
    CHARSET_MISMATCH,

    /**
     * Endpoint returned an empty body when at least the JS prelude was expected. Distinct from
     * {@link #RESPONSE_TOO_SMALL} so a single empty response (terminus marker) doesn't mask a
     * gradual size-drop trend.
     */
    EMPTY_RESPONSE,

    /**
     * A jsoup or regex parser threw mid-extract. Reported by the parser context so a fail-fast
     * strict mode and a tolerant production mode share the same diagnostics.
     */
    PARSER_EXCEPTION,

    /**
     * A parsed value violated an invariant the SDK relies on (e.g., negative episode count, year
     * outside 1900..2100, slug containing characters outside {@code [a-z0-9-]}). Treats
     * silently-corrupted data as a hard signal rather than letting it propagate.
     */
    SCHEMA_VIOLATION,

    /**
     * Top-level response did not match any of the known templates (login wall, anime info, episode
     * page, catalog partial, notice feed). Catches new pages or major redesigns we haven't trained
     * the SDK to recognise.
     */
    UNKNOWN_TEMPLATE
}
