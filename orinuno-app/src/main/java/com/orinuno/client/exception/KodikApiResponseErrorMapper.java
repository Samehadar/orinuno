package com.orinuno.client.exception;

import com.orinuno.token.KodikTokenValidator;
import java.util.Map;

/**
 * Inspects a Kodik response payload (typically {@code Map<String, Object>} from {@code
 * KodikApiClient.postForMap}) and converts known application-level error bodies into the typed
 * {@link KodikApiException} hierarchy. Returns {@code null} when the response is OK or the error
 * doesn't match a known class — caller decides whether to wrap it as {@link KodikUpstreamException}
 * or leave the raw map untouched.
 *
 * <p>Token-rejection ({@code "Отсутствует или неверный токен"}) is intentionally NOT mapped to a
 * typed exception here — it stays as the existing {@link
 * com.orinuno.token.KodikTokenException.TokenRejectedException} so the surrounding token-failover
 * loop in {@code KodikApiClient.executeWithTokenFailover} keeps working as before.
 */
public final class KodikApiResponseErrorMapper {

    private KodikApiResponseErrorMapper() {}

    /**
     * Returns a typed exception for the response, or {@code null} if the response carries no
     * recognised error.
     */
    public static KodikApiException mapIfError(Map<String, Object> response) {
        if (response == null) return null;
        Object errorObj = response.get("error");
        if (errorObj == null) return null;
        String error = errorObj.toString();
        if (error.isBlank()) return null;
        if (KodikTokenValidator.INVALID_TOKEN_ERROR.equals(error)) {
            // Handled by the token-failover loop. Caller treats this as "retry with another
            // token" rather than "give up".
            return null;
        }
        if (containsAny(
                error,
                "Не указан",
                "Неправильный тип",
                "Неверный формат",
                "Не существует",
                "Некорректный",
                "Wrong type",
                "Missing parameter")) {
            return new KodikValidationException(error);
        }
        // Unknown error string but Kodik clearly indicated a failure; surface as upstream error
        // (status 200 since the HTTP layer succeeded).
        return new KodikUpstreamException(200, error);
    }

    private static boolean containsAny(String haystack, String... needles) {
        for (String needle : needles) {
            if (haystack.contains(needle)) return true;
        }
        return false;
    }
}
