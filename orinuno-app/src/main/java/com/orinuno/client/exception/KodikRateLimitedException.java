package com.orinuno.client.exception;

/**
 * Kodik issued HTTP 429 (Too Many Requests). Caller should back off and retry later. Today
 * orinuno's per-token rate limiter keeps us well under Kodik's quota, but third-party callers of
 * the typed API (or future bulk operations) still benefit from a typed signal.
 */
public class KodikRateLimitedException extends KodikApiException {

    private final Long retryAfterSeconds;

    public KodikRateLimitedException(Long retryAfterSeconds) {
        super(
                retryAfterSeconds == null
                        ? "Kodik rate-limited the request"
                        : "Kodik rate-limited the request; retry after " + retryAfterSeconds + "s");
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public Long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
