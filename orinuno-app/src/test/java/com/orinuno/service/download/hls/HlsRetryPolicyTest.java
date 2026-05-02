package com.orinuno.service.download.hls;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class HlsRetryPolicyTest {

    @ParameterizedTest
    @ValueSource(ints = {408, 425, 429, 500, 502, 503, 504, 599})
    void retriableStatuses(int status) {
        assertThat(HlsRetryPolicy.isRetriableStatus(status)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(ints = {200, 201, 204, 301, 302, 400, 401, 403, 404, 410, 451, 600, 0, -1})
    void nonRetriableStatuses(int status) {
        assertThat(HlsRetryPolicy.isRetriableStatus(status)).isFalse();
    }

    @ParameterizedTest
    @CsvSource({"250, 1, 250", "250, 2, 500", "250, 4, 1000", "0, 1, 0", "250, 0, 0", "100, -1, 0"})
    void backoffIsLinearWithBaseAndAttempt(long base, int attempt, long expected) {
        assertThat(HlsRetryPolicy.backoffMillis(base, attempt)).isEqualTo(expected);
    }
}
