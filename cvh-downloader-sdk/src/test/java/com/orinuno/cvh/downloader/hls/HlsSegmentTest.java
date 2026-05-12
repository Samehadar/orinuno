package com.orinuno.cvh.downloader.hls;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class HlsSegmentTest {

    @Test
    void durationMayBeNull() {
        HlsSegment s = new HlsSegment("https://cdn/seg1.ts", null);
        assertThat(s.url()).isEqualTo("https://cdn/seg1.ts");
        assertThat(s.durationSeconds()).isNull();
    }

    @Test
    void blankUrlRejected() {
        assertThatThrownBy(() -> new HlsSegment("", 5.0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new HlsSegment("   ", 5.0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new HlsSegment(null, 5.0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
