package com.orinuno.token;

/**
 * Base for token-related failures. Mirrors AnimeParsers' {@code errors.TokenError}. Concrete
 * subclasses:
 *
 * <ul>
 *   <li>{@link NoWorkingTokenException} — the registry has no token with the requested scope.
 *   <li>{@link TokenRejectedException} — Kodik rejected the chosen token with the literal {@code
 *       Отсутствует или неверный токен} payload.
 * </ul>
 */
public class KodikTokenException extends RuntimeException {

    public KodikTokenException(String message) {
        super(message);
    }

    public KodikTokenException(String message, Throwable cause) {
        super(message, cause);
    }

    /** Raised when the registry cannot supply a token authorised for a given function. */
    public static class NoWorkingTokenException extends KodikTokenException {
        public NoWorkingTokenException(String message) {
            super(message);
        }
    }

    /** Raised after Kodik refuses every available token for a given function. */
    public static class TokenRejectedException extends KodikTokenException {
        public TokenRejectedException(String message) {
            super(message);
        }
    }
}
