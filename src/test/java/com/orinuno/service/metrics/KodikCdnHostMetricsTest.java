package com.orinuno.service.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class KodikCdnHostMetricsTest {

    private SimpleMeterRegistry registry;
    private KodikCdnHostMetrics metrics;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        metrics = new KodikCdnHostMetrics(registry);
    }

    @Test
    @DisplayName("recordDecodedUrl: increments counter tagged with the URL host")
    void recordsHostFromValidUrl() {
        metrics.recordDecodedUrl("https://cloud.solodcdn.com/useruploads/abc/def:202604/720.mp4");
        metrics.recordDecodedUrl("https://cloud.solodcdn.com/useruploads/xxx/yyy:202604/480.mp4");

        Counter counter =
                registry.find(KodikCdnHostMetrics.METRIC_NAME)
                        .tag("host", "cloud.solodcdn.com")
                        .counter();

        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(2.0);
        assertThat(metrics.seenHosts()).contains("cloud.solodcdn.com");
    }

    @Test
    @DisplayName("recordDecodedUrl: separate counters per distinct host")
    void recordsHostsSeparately() {
        metrics.recordDecodedUrl("https://cloud.solodcdn.com/a/720.mp4");
        metrics.recordDecodedUrl("https://cloud.kodik-storage.com/b/720.mp4");

        assertThat(
                        registry.find(KodikCdnHostMetrics.METRIC_NAME)
                                .tag("host", "cloud.solodcdn.com")
                                .counter()
                                .count())
                .isEqualTo(1.0);
        assertThat(
                        registry.find(KodikCdnHostMetrics.METRIC_NAME)
                                .tag("host", "cloud.kodik-storage.com")
                                .counter()
                                .count())
                .isEqualTo(1.0);
    }

    @Test
    @DisplayName("recordDecodedUrl: lower-cases host (Prometheus label hygiene)")
    void lowerCasesHost() {
        metrics.recordDecodedUrl("https://CDN.Example.COM/720.mp4");

        Counter counter =
                registry.find(KodikCdnHostMetrics.METRIC_NAME)
                        .tag("host", "cdn.example.com")
                        .counter();

        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("recordDecodedUrl: null/blank URL goes into _invalid bucket")
    void recordsNullAsInvalid() {
        metrics.recordDecodedUrl(null);
        metrics.recordDecodedUrl("");
        metrics.recordDecodedUrl("   ");

        Counter counter =
                registry.find(KodikCdnHostMetrics.METRIC_NAME)
                        .tag("host", KodikCdnHostMetrics.INVALID_LABEL)
                        .counter();

        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(3.0);
    }

    @Test
    @DisplayName("recordDecodedUrl: malformed URL goes into _invalid bucket")
    void recordsMalformedAsInvalid() {
        metrics.recordDecodedUrl("not-a-url");
        metrics.recordDecodedUrl("http:// broken url with spaces");

        Counter counter =
                registry.find(KodikCdnHostMetrics.METRIC_NAME)
                        .tag("host", KodikCdnHostMetrics.INVALID_LABEL)
                        .counter();

        assertThat(counter).isNotNull();
        assertThat(counter.count()).isGreaterThanOrEqualTo(1.0);
    }

    @Test
    @DisplayName("recordDecodedUrl: cardinality cap folds excess hosts into _overflow bucket")
    void cardinalityCapFoldsIntoOverflow() {
        for (int i = 0; i < KodikCdnHostMetrics.MAX_DISTINCT_HOSTS + 5; i++) {
            metrics.recordDecodedUrl("https://cdn-" + i + ".example.com/file.mp4");
        }

        Counter overflow =
                registry.find(KodikCdnHostMetrics.METRIC_NAME)
                        .tag("host", KodikCdnHostMetrics.OVERFLOW_LABEL)
                        .counter();

        assertThat(overflow).isNotNull();
        assertThat(overflow.count()).isGreaterThanOrEqualTo(5.0);
    }

    @Test
    @DisplayName("extractHost: handles realistic Kodik CDN URLs")
    void extractsHostFromRealisticUrls() {
        assertThat(
                        KodikCdnHostMetrics.extractHost(
                                "https://cloud.solodcdn.com/useruploads/abc/def:2026042712/720.mp4"))
                .isEqualTo("cloud.solodcdn.com");
        assertThat(
                        KodikCdnHostMetrics.extractHost(
                                "https://cloud.solodcdn.com/useruploads/abc/def:2026042712/720.mp4:hls:manifest.m3u8"))
                .isEqualTo("cloud.solodcdn.com");
    }
}
