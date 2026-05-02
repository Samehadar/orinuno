package com.orinuno.service.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import java.net.URI;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class KodikCdnHostMetrics {

    static final String METRIC_NAME = "orinuno.kodik.cdn.host";
    static final String OVERFLOW_LABEL = "_overflow";
    static final String INVALID_LABEL = "_invalid";
    static final int MAX_DISTINCT_HOSTS = 50;

    private final MeterRegistry meterRegistry;
    private final ConcurrentMap<String, Counter> counters = new ConcurrentHashMap<>();
    private final Set<String> seenHosts = ConcurrentHashMap.newKeySet();

    public KodikCdnHostMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    public void recordDecodedUrl(String url) {
        String host = extractHost(url);
        Counter counter = counters.computeIfAbsent(host, this::buildCounter);
        counter.increment();

        if (seenHosts.add(host) && !host.startsWith("_")) {
            log.info("📡 New Kodik CDN host observed: {}", host);
        }
    }

    public Set<String> seenHosts() {
        return Set.copyOf(seenHosts);
    }

    private Counter buildCounter(String host) {
        if (counters.size() >= MAX_DISTINCT_HOSTS) {
            log.warn(
                    "⚠️ Kodik CDN host metric cardinality cap reached ({}). Folding host '{}' into"
                            + " '{}' bucket.",
                    MAX_DISTINCT_HOSTS,
                    host,
                    OVERFLOW_LABEL);
            return counters.computeIfAbsent(
                    OVERFLOW_LABEL,
                    overflow ->
                            Counter.builder(METRIC_NAME)
                                    .description(
                                            "Distinct Kodik CDN hosts observed in decoded mp4"
                                                    + " links")
                                    .tags(Tags.of("host", overflow))
                                    .register(meterRegistry));
        }

        return Counter.builder(METRIC_NAME)
                .description("Distinct Kodik CDN hosts observed in decoded mp4 links")
                .tags(Tags.of("host", host))
                .register(meterRegistry);
    }

    static String extractHost(String url) {
        if (url == null || url.isBlank()) {
            return INVALID_LABEL;
        }
        try {
            String host = URI.create(url).getHost();
            if (host == null || host.isBlank()) {
                return INVALID_LABEL;
            }
            return host.toLowerCase();
        } catch (IllegalArgumentException e) {
            return INVALID_LABEL;
        }
    }
}
