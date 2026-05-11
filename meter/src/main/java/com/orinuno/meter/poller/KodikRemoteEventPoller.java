/*
 * KodikRemoteEventPoller — ADR 0018 Phase 5.5.
 *
 * Moved from orinuno-app into meter as part of the catalog write-path migration.
 * Polls orinuno-source-kodik's /api/v1/source-events/ready, watermarked on
 * Provenance.fetchedAt, and feeds each SourceCatalogEvent into the in-process
 * CatalogSinkEventEmitter which writes the canonical catalog_* rows in the
 * shared orinuno_catalog schema. orinuno-app reads from the same schema with
 * read-only grants (Phase 5.4 + 5.9).
 *
 * Failure model unchanged from the orinuno-app version:
 *   - Network / 5xx / parsing errors → caught, logged at WARN, watermark
 *     untouched. Next tick retries the same window so events are never lost.
 *   - Per-event emit() exceptions are already swallowed inside
 *     CatalogSinkEventEmitter (idempotent design).
 *   - L1 read-path (orinuno-app /api/v1/kodik/* reverse-proxy) is unaffected
 *     by poller errors — failure isolation per ADR 0018 trigger #3.
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
 * Scheduled poller that incrementally ingests {@link SourceCatalogEvent} payloads from {@code
 * orinuno-source-kodik}'s {@code /api/v1/source-events/ready}.
 *
 * <p>Gating: {@code @ConditionalOnProperty(orinuno.source-kodik.base-url)} — if unset, meter boots
 * without polling (skeleton / monolith deploys). With the URL set, meter polls and writes to the
 * canonical catalog_* tables in the shared schema.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "orinuno.source-kodik", name = "base-url")
public class KodikRemoteEventPoller {

    /** Watermark row key — also the wire form on {@link SourceCatalogEvent#identifier()}. */
    private static final String SOURCE_TYPE = "kodik";

    private static final ParameterizedTypeReference<List<SourceCatalogEvent>> EVENT_LIST =
            new ParameterizedTypeReference<>() {};

    private final WebClient client;
    private final RemoteSourceWatermarkRepository watermarkRepository;
    private final CatalogSinkEventEmitter emitter;
    private final Clock clock;
    private final int batchSize;

    public KodikRemoteEventPoller(
            WebClient.Builder builder,
            @Value("${orinuno.source-kodik.base-url}") String baseUrl,
            @Value("${orinuno.source-kodik.poll-batch-size:50}") int batchSize,
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
                "Kodik remote-event poller ENABLED — base-url={}, batch-size={}",
                baseUrl,
                this.batchSize);
    }

    @Scheduled(
            fixedDelayString = "${orinuno.source-kodik.poll-interval-ms:300000}",
            initialDelayString = "${orinuno.source-kodik.poll-initial-delay-ms:30000}")
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
                        "Kodik poller: ingested {} event(s); watermark advanced from {} to {}",
                        events.size(),
                        since,
                        newWatermark);
            } else {
                log.debug("Kodik poller: no new events (watermark={})", since);
            }
        } catch (RuntimeException e) {
            String summary = e.getClass().getSimpleName() + ": " + truncate(e.getMessage());
            watermarkRepository.upsert(SOURCE_TYPE, since, pollStart, 0, summary);
            log.warn(
                    "Kodik poller: tick failed ({}) — watermark unchanged at {}, will retry",
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
