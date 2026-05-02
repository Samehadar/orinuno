package com.orinuno.client.exception;

/**
 * Base for all errors raised by {@link com.orinuno.client.KodikApiClient} that are NOT specific to
 * the token registry (token-registry errors live under {@link
 * com.orinuno.token.KodikTokenException}).
 *
 * <p>Created in API-5. The previous code surfaced upstream errors as opaque {@link
 * RuntimeException} or {@link
 * org.springframework.web.reactive.function.client.WebClientResponseException}, forcing callers to
 * grep error strings to react. This hierarchy lets callers do typed catch blocks and gives Spring's
 * {@code @ExceptionHandler} a stable surface to map to HTTP responses.
 *
 * <p>Concrete subclasses:
 *
 * <ul>
 *   <li>{@link KodikValidationException} — Kodik returned an application-level error (e.g. missing
 *       search param, invalid {@code types} enum). HTTP status is 200 with body {@code
 *       {"error":"…"}} or 500 with the same body. Caller should fix the request, not retry.
 *   <li>{@link KodikRateLimitedException} — Kodik issued HTTP 429 (rate limit). Caller can back off
 *       and retry.
 *   <li>{@link KodikUpstreamException} — generic 5xx that didn't carry a recognisable error body.
 *       Caller can retry but should not assume idempotency.
 * </ul>
 */
public abstract class KodikApiException extends RuntimeException {

    protected KodikApiException(String message) {
        super(message);
    }

    protected KodikApiException(String message, Throwable cause) {
        super(message, cause);
    }
}
