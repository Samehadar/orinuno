package com.orinuno.service;

import com.orinuno.client.KodikApiClient;
import com.orinuno.client.dto.KodikSearchRequest;
import com.orinuno.client.dto.KodikSearchResponse;
import com.orinuno.configuration.OrinunoProperties;
import com.orinuno.mapper.EntityFactory;
import com.orinuno.model.KodikContent;
import com.orinuno.model.KodikEpisodeVariant;
import com.orinuno.model.ParseRequestPhase;
import com.orinuno.model.dto.ParseRequestDto;
import com.orinuno.repository.EpisodeVariantRepository;
import com.orinuno.service.metrics.KodikCdnHostMetrics;
import com.orinuno.service.metrics.KodikDecoderMetrics;
import com.orinuno.service.requestlog.ProgressReporter;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

@Slf4j
@Service
@RequiredArgsConstructor
public class ParserService {

    private final KodikApiClient kodikApiClient;
    private final ContentService contentService;
    private final KodikVideoDecoderService decoderService;
    private final EpisodeVariantRepository episodeVariantRepository;
    private final OrinunoProperties properties;
    private final KodikCdnHostMetrics kodikCdnHostMetrics;
    private final KodikDecoderMetrics decoderMetrics;

    public Mono<List<KodikContent>> search(ParseRequestDto request) {
        return searchInternal(request, ProgressReporter.NOOP);
    }

    /**
     * Internal search overload that reports phase transitions and decode progress to the supplied
     * {@link ProgressReporter}. Used by RequestWorker for async parse-request processing.
     */
    public Mono<List<KodikContent>> searchInternal(
            ParseRequestDto request, ProgressReporter reporter) {
        KodikSearchRequest searchRequest =
                KodikSearchRequest.builder()
                        .title(request.getTitle())
                        .kinopoiskId(request.getKinopoiskId())
                        .imdbId(request.getImdbId())
                        .shikimoriId(request.getShikimoriId())
                        .build();

        return kodikApiClient
                .search(searchRequest)
                .flatMap(
                        response ->
                                processSearchResults(response, request.isDecodeLinks(), reporter));
    }

    private Mono<List<KodikContent>> processSearchResults(
            KodikSearchResponse response, boolean decodeLinks, ProgressReporter reporter) {
        if (response.getResults() == null || response.getResults().isEmpty()) {
            log.info("📭 No results from Kodik API");
            return Mono.just(List.of());
        }

        log.info("📦 Processing {} results from Kodik API", response.getResults().size());

        return Flux.fromIterable(response.getResults())
                .map(
                        result -> {
                            KodikContent content = EntityFactory.createContent(result);
                            content = contentService.findOrCreateContent(content);

                            List<KodikEpisodeVariant> variants =
                                    EntityFactory.createVariants(content.getId(), result);
                            contentService.saveVariants(variants);

                            return content;
                        })
                .collectList()
                .flatMap(
                        contents -> {
                            if (!decodeLinks) {
                                return Mono.just(contents);
                            }
                            int totalPending = countPendingVariants(contents);
                            reporter.phaseTransition(ParseRequestPhase.DECODING);
                            reporter.update(0, totalPending);
                            if (totalPending == 0) {
                                return Mono.just(contents);
                            }
                            AtomicInteger decodedCounter = new AtomicInteger();
                            return decodeAllPending(
                                            contents, reporter, decodedCounter, totalPending)
                                    .thenReturn(contents);
                        });
    }

    private int countPendingVariants(List<KodikContent> contents) {
        return contents.stream()
                .mapToInt(c -> episodeVariantRepository.findByContentIdWithoutMp4(c.getId()).size())
                .sum();
    }

    public Mono<Void> decodeForContent(Long contentId) {
        List<KodikEpisodeVariant> pending =
                episodeVariantRepository.findByContentIdWithoutMp4(contentId);
        if (pending.isEmpty()) {
            log.info("✅ All variants for content_id={} already decoded", contentId);
            return Mono.empty();
        }

        log.info("🔓 Decoding {} pending variants for content_id={}", pending.size(), contentId);

        return Flux.fromIterable(pending)
                .delayElements(Duration.ofMillis(properties.getKodik().getRequestDelayMs()))
                .flatMap(variant -> decodeVariant(variant), 1)
                .then();
    }

    /** Decode exactly one variant by id. Returns true if decode was performed, false if skipped. */
    public Mono<Boolean> decodeForVariant(Long variantId, boolean force) {
        return Mono.fromCallable(() -> episodeVariantRepository.findById(variantId))
                .flatMap(
                        opt -> {
                            if (opt.isEmpty()) {
                                return Mono.error(
                                        new IllegalArgumentException(
                                                "Variant not found: " + variantId));
                            }
                            KodikEpisodeVariant variant = opt.get();
                            if (!force && variant.getMp4Link() != null) {
                                log.info(
                                        "ℹ️ Variant id={} already has mp4_link, skipping (pass"
                                                + " force=true to redo)",
                                        variantId);
                                return Mono.just(false);
                            }
                            log.info(
                                    "🔓 Decoding single variant id={}, force={}", variantId, force);
                            return decodeVariant(variant).thenReturn(true);
                        });
    }

    /** PF-I3: Force re-decode all variants for content (ignoring existing mp4_link). */
    public Mono<Void> forceDecodeForContent(Long contentId) {
        List<KodikEpisodeVariant> all = episodeVariantRepository.findByContentId(contentId);
        List<KodikEpisodeVariant> withKodikLink =
                all.stream().filter(v -> v.getKodikLink() != null).toList();

        if (withKodikLink.isEmpty()) {
            log.info("📭 No variants with kodik_link for content_id={}", contentId);
            return Mono.empty();
        }

        log.info(
                "🔄 Force re-decoding {} variants for content_id={}",
                withKodikLink.size(),
                contentId);

        return Flux.fromIterable(withKodikLink)
                .delayElements(Duration.ofMillis(properties.getKodik().getRequestDelayMs()))
                .flatMap(variant -> decodeVariant(variant), 1)
                .then();
    }

    /**
     * PF-I3: Refresh expired mp4 links. Triggered by {@code DecoderMaintenanceScheduler} on the
     * dedicated {@code orinuno-decoder-maint-*} pool. Bounded by {@code
     * orinuno.decoder.maintenance.max-batch-per-tick} (cap) and {@code
     * orinuno.decoder.maintenance.tick-timeout-seconds} (wall-clock) so a single bad batch can't
     * pin the maintenance pool indefinitely. See TECH_DEBT TD-PR-5.
     */
    public void refreshExpiredLinks() {
        int ttlHours = properties.getDecoder().getLinkTtlHours();
        int batchSize = boundedBatchSize();
        List<KodikEpisodeVariant> expired =
                episodeVariantRepository.findExpiredLinks(ttlHours, batchSize);

        if (expired.isEmpty()) {
            log.debug("✅ No expired mp4 links to refresh");
            return;
        }

        log.info(
                "🔄 Refreshing {} expired mp4 links (TTL={}h, cap={})",
                expired.size(),
                ttlHours,
                batchSize);
        runBoundedDecodeBatch("refreshExpiredLinks", expired);
    }

    /**
     * PF-I4: Retry previously failed decodes. Same bounding rules as {@link
     * #refreshExpiredLinks()}. See TECH_DEBT TD-PR-5.
     */
    public void retryFailedDecodes() {
        int batchSize = boundedBatchSize();
        List<KodikEpisodeVariant> failed = episodeVariantRepository.findFailedDecode(batchSize);

        if (failed.isEmpty()) {
            log.debug("✅ No failed decodes to retry");
            return;
        }

        log.info("🔁 Retrying {} previously failed decodes (cap={})", failed.size(), batchSize);
        runBoundedDecodeBatch("retryFailedDecodes", failed);
    }

    private int boundedBatchSize() {
        int rawBatch = properties.getDecoder().getRefreshBatchSize();
        int cap = properties.getDecoder().getMaintenance().getMaxBatchPerTick();
        return Math.max(1, Math.min(rawBatch, cap));
    }

    private void runBoundedDecodeBatch(String label, List<KodikEpisodeVariant> batch) {
        Duration tickTimeout =
                Duration.ofSeconds(
                        properties.getDecoder().getMaintenance().getTickTimeoutSeconds());
        try {
            Flux.fromIterable(batch)
                    .delayElements(Duration.ofMillis(properties.getKodik().getRequestDelayMs()))
                    .flatMap(variant -> decodeVariant(variant), 1)
                    .then()
                    .block(tickTimeout);
        } catch (IllegalStateException timeout) {
            log.warn(
                    "⚠️ {} aborted: tick wall-clock timeout {}s exceeded ({} variants in batch)",
                    label,
                    tickTimeout.toSeconds(),
                    batch.size());
        } catch (RuntimeException ex) {
            log.warn("⚠️ {} aborted: {}", label, ex.toString());
        }
    }

    private Mono<Void> decodeAllPending(
            List<KodikContent> contents,
            ProgressReporter reporter,
            AtomicInteger decodedCounter,
            int totalPending) {
        return Flux.fromIterable(contents)
                .flatMap(
                        content ->
                                decodeForContentReporting(
                                        content.getId(), reporter, decodedCounter, totalPending),
                        1)
                .then();
    }

    private Mono<Void> decodeForContentReporting(
            Long contentId,
            ProgressReporter reporter,
            AtomicInteger decodedCounter,
            int totalPending) {
        List<KodikEpisodeVariant> pending =
                episodeVariantRepository.findByContentIdWithoutMp4(contentId);
        if (pending.isEmpty()) {
            return Mono.empty();
        }

        return Flux.fromIterable(pending)
                .delayElements(Duration.ofMillis(properties.getKodik().getRequestDelayMs()))
                .flatMap(
                        variant ->
                                decodeVariant(variant)
                                        .doOnSuccess(
                                                v ->
                                                        reporter.update(
                                                                decodedCounter.incrementAndGet(),
                                                                totalPending)),
                        1)
                .then();
    }

    /** PF-I4: decode with retry backoff. */
    private Mono<Void> decodeVariant(KodikEpisodeVariant variant) {
        if (variant.getKodikLink() == null) {
            log.warn("⚠️ Variant id={} has no kodik_link, skipping decode", variant.getId());
            return Mono.empty();
        }

        int maxRetries = properties.getDecoder().getMaxRetries();

        return decoderService
                .decode(variant.getKodikLink())
                .retryWhen(
                        Retry.backoff(maxRetries, Duration.ofSeconds(2))
                                .doBeforeRetry(
                                        signal ->
                                                log.warn(
                                                        "🔁 Retry #{} for variant id={}",
                                                        signal.totalRetries() + 1,
                                                        variant.getId())))
                .doOnSuccess(
                        videoLinks -> {
                            Map.Entry<String, String> best = pickBestQualityEntry(videoLinks);
                            if (best != null) {
                                String bestLink = best.getValue();
                                episodeVariantRepository.updateMp4Link(variant.getId(), bestLink);
                                kodikCdnHostMetrics.recordDecodedUrl(bestLink);
                                if (decoderMetrics != null) {
                                    decoderMetrics.recordPickedQuality(best.getKey());
                                }
                                log.info(
                                        "✅ Decoded variant id={}: {} qualities, best={} ({}p)",
                                        variant.getId(),
                                        videoLinks.size(),
                                        bestLink.substring(0, Math.min(60, bestLink.length())),
                                        best.getKey());
                            }
                        })
                .doOnError(
                        e ->
                                log.error(
                                        "❌ Failed to decode variant id={} after {} retries: {}",
                                        variant.getId(),
                                        maxRetries,
                                        e.getMessage()))
                .onErrorResume(e -> Mono.empty())
                .then();
    }

    static String selectBestQuality(Map<String, String> videoLinks) {
        Map.Entry<String, String> entry = pickBestQualityEntry(videoLinks);
        return entry == null ? null : entry.getValue();
    }

    /**
     * Like {@link #selectBestQuality(Map)} but returns the full entry so callers can record both
     * the quality bucket (key) and the URL (value). Used by Prometheus quality metric per ADR 0004.
     */
    static Map.Entry<String, String> pickBestQualityEntry(Map<String, String> videoLinks) {
        if (videoLinks == null || videoLinks.isEmpty()) return null;

        return videoLinks.entrySet().stream()
                .filter(e -> !e.getKey().startsWith("_"))
                .filter(e -> e.getValue() != null && e.getValue().startsWith("http"))
                .max(
                        (a, b) -> {
                            try {
                                return Integer.compare(
                                        Integer.parseInt(a.getKey()), Integer.parseInt(b.getKey()));
                            } catch (NumberFormatException e) {
                                return 0;
                            }
                        })
                .orElse(null);
    }
}
