/*
 * KodikRemoteEventPoller — ADR 0018 Phase 2.11.
 *
 * Polls orinuno-source-kodik's /api/v1/source-events/ready, watermarked on
 * Provenance.fetchedAt, and feeds each SourceCatalogEvent into the existing
 * in-process CatalogSinkEventEmitter (ADR 0017). This is the bridge that
 * keeps L3 catalog up-to-date while the source-of-truth L1 has already moved
 * out of orinuno-app and into the standalone source service.
 *
 * Phase-temporary: when Phase 5 extracts the OSS meter as a separate service,
 * this poller moves along with the catalog write-path into that new module
 * and orinuno-app stops holding catalog state altogether.
 *
 * Failure model:
 *   - Network / 5xx / parsing errors → caught, logged at WARN, watermark
 *     untouched. Next tick retries the same window so events are never lost.
 *   - Per-event emit() exceptions are already swallowed inside
 *     CatalogSinkEventEmitter (idempotent design). We still advance the
 *     watermark for the batch because the source row will reappear on its
 *     next updated_at bump.
 *   - L1 read-path (the reverse-proxied /api/v1/kodik/*) is unaffected by
 *     poller errors — failure isolation per ADR 0018 trigger #3.
 */
package com.orinuno.service.orchestration;

import com.orinuno.catalog.ingestion.CatalogSinkEventEmitter;
import com.orinuno.contract.source.SourceCatalogEvent;
import com.orinuno.model.RemoteSourceWatermark;
import com.orinuno.repository.RemoteSourceWatermarkRepository;
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
 * <p>Gating: identical {@code @ConditionalOnProperty} as {@link
 * com.orinuno.configuration.KodikUpstreamProxyFilter} — if {@code orinuno.source-kodik.base-url} is
 * unset the standalone service does not exist, the reverse-proxy is dormant, and orinuno-app's own
 * {@code KodikCatalogIngestion} keeps emitting events directly into the local in-process emitter.
 * With {@code base-url} set, the source-of-truth has moved and we read it over HTTP.
 *
 * <p>Cadence is governed by {@code orinuno.source-kodik.poll-interval-ms} (default 5 minutes).
 * Catalog rows change on Kodik dump cycles — once every 24h on average, occasionally more during
 * staff updates — so 5 min keeps "freshness" within a sensible bound while staying nowhere near the
 * upstream's rate-limit ceiling. {@code orinuno.source-kodik.poll-batch-size} caps each call; the
 * endpoint clamps to 200 server-side regardless.
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
        // Codec budget matches KodikUpstreamProxyFilter — Kodik /list-shaped payloads can run
        // multi-megabyte in a single batch, and the poller must not bottleneck on Spring's
        // default 256 KiB cap.
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

    /**
     * Cron tick. Defaults to every 5 minutes; override via {@code
     * orinuno.source-kodik.poll-interval-ms}. The fixed-delay form means the next tick starts
     * {@code interval} after the previous one *finished* — slow polls do not pile up.
     */
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
            // Watermark untouched — next tick retries the same window. Record the error so the
            // health endpoint can surface a hung upstream without grepping logs.
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
