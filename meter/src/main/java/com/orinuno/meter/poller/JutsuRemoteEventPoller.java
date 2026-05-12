/*
 * JutsuRemoteEventPoller — ADR 0019 Phase 4.11.
 *
 * Symmetric to KodikRemoteEventPoller (Phase 5.5). Polls
 * orinuno-source-jutsu's /api/v1/source-events/ready, watermarked on
 * Provenance.fetchedAt via the shared RemoteSourceWatermarkRepository
 * (sourceType="jutsu"), and feeds each SourceCatalogEvent into the
 * in-process CatalogSinkEventEmitter writing canonical catalog_* rows in
 * the shared orinuno_catalog schema.
 *
 * Failure model identical to the Kodik poller:
 *   - Network / 5xx / parse errors → caught, logged WARN, watermark
 *     untouched, retried on next tick.
 *   - Per-event emit() exceptions swallowed inside CatalogSinkEventEmitter
 *     (idempotent design).
 *   - L1 read-path (orinuno-app /api/v1/sources/jutsu/* reverse-proxy)
 *     unaffected — failure isolation per ADR 0019 trigger #3.
 */
package com.orinuno.meter.poller;

import com.orinuno.contract.source.SourceCatalogEvent;
import com.orinuno.meter.catalog.ingestion.CatalogSinkEventEmitter;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Gated by {@code orinuno.source-jutsu.base-url} — unset → bean absent → meter polls only Kodik.
 * Set → meter polls both per-source services in parallel @Scheduled cycles.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "orinuno.source-jutsu", name = "base-url")
public class JutsuRemoteEventPoller {

    /** Watermark row key — open-string wire form from SourceIdentifier#sourceType(). */
    private static final String SOURCE_TYPE = "jutsu";

    private static final ParameterizedTypeReference<List<SourceCatalogEvent>> EVENT_LIST =
            new ParameterizedTypeReference<>() {};

    private final WebClient client;
    private final RemoteSourceWatermarkRepository watermarkRepository;
    private final CatalogSinkEventEmitter emitter;
    private final Clock clock;
    private final int batchSize;

    public JutsuRemoteEventPoller(
            WebClient.Builder builder,
            @Value("${orinuno.source-jutsu.base-url}") String baseUrl,
            @Value("${orinuno.source-jutsu.poll-batch-size:50}") int batchSize,
            RemoteSourceWatermarkRepository watermarkRepository,
            CatalogSinkEventEmitter emitter,
            Clock clock) {
        ExchangeStrategies strategies =
                ExchangeStrategies.builder()
                        .codecs(codecs -> codecs.defaultCodecs().maxInMemorySize(16 * 1024 * 1024))
                        .build();
        this.client = builder.baseUrl(baseUrl).exchangeStrategies(strategies).build();
        this.watermarkRepository = watermarkRepository;
        this.emitter = emitter;
        this.clock = clock;
        this.batchSize = batchSize > 0 ? batchSize : 50;
        log.info(
                "Jutsu remote-event poller ENABLED — base-url={}, batch-size={}",
                baseUrl,
                this.batchSize);
    }

    @Scheduled(
            fixedDelayString = "${orinuno.source-jutsu.poll-interval-ms:300000}",
            initialDelayString = "${orinuno.source-jutsu.poll-initial-delay-ms:30000}")
    public void pollOnce() {
        LocalDateTime pollStart = LocalDateTime.now(clock);
        LocalDateTime since =
                watermarkRepository
                        .findBySourceType(SOURCE_TYPE)
                        .map(RemoteSourceWatermark::getLastFetchedAt)
                        .orElse(null);
        try {
            List<SourceCatalogEvent> events = fetch(since);
            LocalDateTime newWatermark = applyEvents(events, since);
            watermarkRepository.upsert(SOURCE_TYPE, newWatermark, pollStart, events.size(), null);
            if (!events.isEmpty()) {
                log.info(
                        "Jutsu poller: ingested {} event(s); watermark advanced from {} to {}",
                        events.size(),
                        since,
                        newWatermark);
            } else {
                log.debug("Jutsu poller: no new events (watermark={})", since);
            }
        } catch (RuntimeException e) {
            String summary = e.getClass().getSimpleName() + ": " + truncate(e.getMessage());
            watermarkRepository.upsert(SOURCE_TYPE, since, pollStart, 0, summary);
            log.warn(
                    "Jutsu poller: tick failed ({}) — watermark unchanged at {}, will retry",
                    summary,
                    since);
        }
    }

    private List<SourceCatalogEvent> fetch(LocalDateTime since) {
        return client.get()
                .uri(
                        uri -> {
                            var b =
                                    uri.path("/api/v1/source-events/ready")
                                            .queryParam("limit", batchSize);
                            if (since != null) {
                                b.queryParam("updatedSince", since.toString());
                            }
                            return b.build();
                        })
                .retrieve()
                .bodyToMono(EVENT_LIST)
                .block();
    }

    private LocalDateTime applyEvents(List<SourceCatalogEvent> events, LocalDateTime fallback) {
        if (events == null || events.isEmpty()) {
            return fallback;
        }
        LocalDateTime maxFetchedAt = fallback;
        for (SourceCatalogEvent event : events) {
            emitter.emit(event);
            LocalDateTime fetchedAt =
                    Optional.ofNullable(event.provenance())
                            .map(p -> p.fetchedAt())
                            .map(instant -> LocalDateTime.ofInstant(instant, ZoneOffset.UTC))
                            .orElse(null);
            if (fetchedAt != null && (maxFetchedAt == null || fetchedAt.isAfter(maxFetchedAt))) {
                maxFetchedAt = fetchedAt;
            }
        }
        return maxFetchedAt;
    }

    private static String truncate(String s) {
        if (s == null) return null;
        return s.length() <= 480 ? s : s.substring(0, 480) + "…";
    }
}
