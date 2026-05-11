package com.orinuno.cvh.downloader;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class CvhDownloaderConfigTest {

    @Test
    void defaultsAreSane() {
        CvhDownloaderConfig c = CvhDownloaderConfig.builder().build();
        assertThat(c.outputBaseDir()).isEqualTo(Path.of("./data/videos"));
        assertThat(c.segmentConcurrency()).isEqualTo(8);
        assertThat(c.segmentRetryMaxAttempts()).isEqualTo(4);
        assertThat(c.segmentRetryBaseDelayMs()).isEqualTo(250);
        assertThat(c.ffmpegBinary()).isEqualTo("ffmpeg");
        assertThat(c.ffmpegTimeoutSeconds()).isEqualTo(600);
        assertThat(c.maxBytesPerFile()).isEqualTo(5L * 1024 * 1024 * 1024);
        assertThat(c.userAgent()).contains("Chrome");
    }

    @Test
    void overrideFieldsFlowThrough() {
        CvhDownloaderConfig c =
                CvhDownloaderConfig.builder()
                        .outputBaseDir(Path.of("/tmp/x"))
                        .segmentConcurrency(2)
                        .segmentRetryMaxAttempts(0)
                        .segmentRetryBaseDelayMs(0)
                        .ffmpegBinary("/usr/local/bin/ffmpeg")
                        .ffmpegTimeoutSeconds(30)
                        .maxBytesPerFile(1024)
                        .userAgent("UA")
                        .build();
        assertThat(c.outputBaseDir()).isEqualTo(Path.of("/tmp/x"));
        assertThat(c.segmentConcurrency()).isEqualTo(2);
        assertThat(c.maxBytesPerFile()).isEqualTo(1024);
    }

    @Test
    void zeroConcurrencyRejected() {
        assertThatThrownBy(() -> CvhDownloaderConfig.builder().segmentConcurrency(0).build())
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void blankFfmpegRejected() {
        assertThatThrownBy(() -> CvhDownloaderConfig.builder().ffmpegBinary("").build())
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void zeroMaxBytesRejected() {
        assertThatThrownBy(() -> CvhDownloaderConfig.builder().maxBytesPerFile(0).build())
                .isInstanceOf(IllegalArgumentException.class);
    }
}
