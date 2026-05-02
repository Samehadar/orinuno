package com.orinuno.client.embed;

/**
 * Errors raised by the Kodik embed-link resolver. Mirrors the granularity of {@link
 * com.orinuno.token.KodikTokenException} so controllers can map specific subtypes to HTTP status
 * codes.
 */
public class KodikEmbedException extends RuntimeException {

    public KodikEmbedException(String message) {
        super(message);
    }

    public KodikEmbedException(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * Kodik returned {@code "found": false} for the requested id — no content matches the supplied
     * external id. Maps to HTTP 404.
     */
    public static class NotFoundException extends KodikEmbedException {
        public NotFoundException(String message) {
            super(message);
        }
    }

    /**
     * Kodik returned a non-token-related {@code "error"} field (rate limit, malformed query,
     * upstream issue). Maps to HTTP 502.
     */
    public static class UpstreamException extends KodikEmbedException {
        public UpstreamException(String message) {
            super(message);
        }
    }

    /**
     * Kodik returned a 2xx body but with no usable {@code link} / unknown shape. Should never
     * happen in steady state — fires schema-drift suspicion. Maps to HTTP 502.
     */
    public static class MalformedResponseException extends KodikEmbedException {
        public MalformedResponseException(String message) {
            super(message);
        }
    }
}
