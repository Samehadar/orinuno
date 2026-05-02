package com.orinuno.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.orinuno.service.metrics.KodikDecoderMetrics.UpstreamErrorClass;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class KodikUpstreamErrorClassifierTest {

    @ParameterizedTest(name = "[{0}] body matches {1}")
    @CsvSource(
            value = {
                "REST_TOKEN_BAD       | TOKEN_INVALID         | {\"error\":\"Отсутствует или"
                        + " неверный токен\"}",
                "REST_NO_FILTER       | MISSING_SEARCH_PARAM  | {\"error\":\"Не указан хотя бы один"
                        + " параметр для поиска\"}",
                "REST_BAD_TYPE        | WRONG_TYPE            | {\"error\":\"Неправильный тип\"}",
                "REST_NULL            | OTHER                 | ",
                "REST_EMPTY           | OTHER                 | ''",
                "REST_HTML_GENERIC    | OTHER                 | <!doctype html>500 error"
            },
            delimiter = '|',
            ignoreLeadingAndTrailingWhitespace = true,
            quoteCharacter = '\'')
    void classifyMatchesKnownPhrases(String label, String expected, String body) {
        UpstreamErrorClass actual = KodikUpstreamErrorClassifier.classify(500, body);
        assertThat(actual.name()).as("[%s] body=%s", label, body).isEqualTo(expected);
    }

    @org.junit.jupiter.api.Test
    @DisplayName(
            "classifyForDecoder remaps token-invalid to signed-params-stale (decoder calls embed"
                    + " token in url-params, not query)")
    void classifyForDecoderRemapsTokenToSignedParams() {
        String body = "{\"error\":\"Отсутствует или неверный токен\"}";
        assertThat(KodikUpstreamErrorClassifier.classify(500, body))
                .isEqualTo(UpstreamErrorClass.TOKEN_INVALID);
        assertThat(KodikUpstreamErrorClassifier.classifyForDecoder(500, body))
                .isEqualTo(UpstreamErrorClass.SIGNED_PARAMS_STALE);
    }

    @org.junit.jupiter.api.Test
    @DisplayName("classifyForDecoder leaves other classes untouched")
    void classifyForDecoderLeavesOthersAlone() {
        assertThat(
                        KodikUpstreamErrorClassifier.classifyForDecoder(
                                500, "{\"error\":\"Неправильный тип\"}"))
                .isEqualTo(UpstreamErrorClass.WRONG_TYPE);
        assertThat(KodikUpstreamErrorClassifier.classifyForDecoder(500, "garbage"))
                .isEqualTo(UpstreamErrorClass.OTHER);
    }
}
