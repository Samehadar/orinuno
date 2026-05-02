package com.orinuno.client.exception;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class KodikApiResponseErrorMapperTest {

    @Test
    @DisplayName("returns null when response has no error key")
    void noErrorReturnsNull() {
        assertThat(KodikApiResponseErrorMapper.mapIfError(Map.of("results", "ok"))).isNull();
        assertThat(KodikApiResponseErrorMapper.mapIfError(Map.of())).isNull();
        assertThat(KodikApiResponseErrorMapper.mapIfError(null)).isNull();
    }

    @Test
    @DisplayName("returns null for token-rejection — token-failover loop owns this case")
    void tokenRejectionReturnsNull() {
        Map<String, Object> resp = Map.of("error", "Отсутствует или неверный токен");
        assertThat(KodikApiResponseErrorMapper.mapIfError(resp)).isNull();
    }

    @Test
    @DisplayName("maps known Russian validation phrases to KodikValidationException")
    void mapsValidationErrors() {
        Map<String, Object> resp1 = Map.of("error", "Не указан хотя бы один параметр для поиска");
        assertThat(KodikApiResponseErrorMapper.mapIfError(resp1))
                .isInstanceOf(KodikValidationException.class);

        Map<String, Object> resp2 = Map.of("error", "Неправильный тип");
        assertThat(KodikApiResponseErrorMapper.mapIfError(resp2))
                .isInstanceOf(KodikValidationException.class);

        Map<String, Object> resp3 = Map.of("error", "Wrong type");
        assertThat(KodikApiResponseErrorMapper.mapIfError(resp3))
                .isInstanceOf(KodikValidationException.class);
    }

    @Test
    @DisplayName("KodikValidationException preserves original Kodik message")
    void validationExceptionPreservesMessage() {
        KodikValidationException ex =
                (KodikValidationException)
                        KodikApiResponseErrorMapper.mapIfError(
                                Map.of("error", "Не указан хотя бы один параметр для поиска"));
        assertThat(ex.getKodikErrorMessage())
                .isEqualTo("Не указан хотя бы один параметр для поиска");
        assertThat(ex.getMessage()).contains("Kodik validation error: ");
    }

    @Test
    @DisplayName("unknown error string falls back to KodikUpstreamException(200, error)")
    void unknownErrorMapsToUpstream() {
        Map<String, Object> resp = Map.of("error", "Some completely new failure mode");
        KodikUpstreamException ex =
                (KodikUpstreamException) KodikApiResponseErrorMapper.mapIfError(resp);
        assertThat(ex).isNotNull();
        assertThat(ex.getHttpStatus()).isEqualTo(200);
        assertThat(ex.getBodyPreview()).contains("Some completely new failure mode");
    }

    @Test
    @DisplayName("blank error string returns null (treated as no error)")
    void blankErrorReturnsNull() {
        Map<String, Object> resp = new HashMap<>();
        resp.put("error", "");
        assertThat(KodikApiResponseErrorMapper.mapIfError(resp)).isNull();
    }
}
