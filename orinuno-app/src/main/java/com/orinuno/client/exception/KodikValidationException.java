package com.orinuno.client.exception;

/**
 * Application-level validation failure reported by Kodik. The request reached Kodik's app code, was
 * parsed, and was rejected for content reasons (missing/invalid params). Caller should not retry
 * without fixing the request.
 *
 * <p>Examples observed in the 2026-05-02 probe (see docs/quirks-and-hacks.md):
 *
 * <ul>
 *   <li>{@code "Не указан хотя бы один параметр для поиска"} — {@code /search} called with no title
 *       / id / kinopoisk / shikimori filter.
 *   <li>{@code "Неправильный тип"} — {@code /translations/v2}, {@code /years} called with an empty
 *       or unknown {@code types} value.
 * </ul>
 *
 * <p>Note: Kodik also reuses the literal {@code "Отсутствует или неверный токен"} for both token
 * failures (handled by {@link com.orinuno.token.KodikTokenException.TokenRejectedException}) AND
 * for stale signed-iframe params on the decoder. Token-rejection is intentionally NOT remapped to
 * this exception so the existing token-failover loop keeps working.
 */
public class KodikValidationException extends KodikApiException {

    private final String kodikErrorMessage;

    public KodikValidationException(String kodikErrorMessage) {
        super("Kodik validation error: " + kodikErrorMessage);
        this.kodikErrorMessage = kodikErrorMessage;
    }

    public String getKodikErrorMessage() {
        return kodikErrorMessage;
    }
}
