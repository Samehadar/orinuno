package com.orinuno.cvh.downloader.hls;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class HlsRetryPolicyTest {

    @Test
    void retriableStatuses() {
        assertThat(HlsRetryPolicy.isRetriableStatus(408)).isTrue();
        assertThat(HlsRetryPolicy.isRetriableStatus(425)).isTrue();
        assertThat(HlsRetryPolicy.isRetriableStatus(429)).isTrue();
        assertThat(HlsRetryPolicy.isRetriableStatus(500)).isTrue();
        assertThat(HlsRetryPolicy.isRetriableStatus(599)).isTrue();
    }

    @Test
    void nonRetriableStatuses() {
        assertThat(HlsRetryPolicy.isRetriableStatus(200)).isFalse();
        assertThat(HlsRetryPolicy.isRetriableStatus(400)).isFalse();
        assertThat(HlsRetryPolicy.isRetriableStatus(403)).isFalse();
        assertThat(HlsRetryPolicy.isRetriableStatus(404)).isFalse();
        assertThat(HlsRetryPolicy.isRetriableStatus(451)).isFalse();
    }

    @Test
    void backoffIsLinear() {
        assertThat(HlsRetryPolicy.backoffMillis(250, 1)).isEqualTo(250);
        assertThat(HlsRetryPolicy.backoffMillis(250, 4)).isEqualTo(1000);
        assertThat(HlsRetryPolicy.backoffMillis(0, 5)).isZero();
        assertThat(HlsRetryPolicy.backoffMillis(250, 0)).isZero();
    }
}
