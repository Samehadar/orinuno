package com.orinuno.jutsu.fallback;

import org.springframework.http.HttpStatus;

/**
 * Thrown when a live-fallback path must short-circuit with a non-2xx response (rate-limited,
 * negatively cached, kill-switched). The controller layer maps the {@link #status} to the actual
 * HTTP response.
 */
public class JutsuLiveFallbackException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final HttpStatus status;
    private final JutsuLiveFallbackOutcome outcome;
    private final long retryAfterSeconds;

    public JutsuLiveFallbackException(
            HttpStatus status,
            JutsuLiveFallbackOutcome outcome,
            String message,
            long retryAfterSeconds) {
        super(message);
        this.status = status;
        this.outcome = outcome;
        this.retryAfterSeconds = Math.max(0, retryAfterSeconds);
    }

    public HttpStatus status() {
        return status;
    }

    public JutsuLiveFallbackOutcome outcome() {
        return outcome;
    }

    public long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
