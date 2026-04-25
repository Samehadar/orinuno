package com.orinuno.service;

import com.orinuno.client.KodikApiClient;
import com.orinuno.client.dto.KodikSearchRequest;
import com.orinuno.client.dto.KodikSearchResponse;
import com.orinuno.configuration.OrinunoProperties;
import com.orinuno.mapper.EntityFactory;
import com.orinuno.model.KodikContent;
import com.orinuno.model.KodikEpisodeVariant;
import com.orinuno.model.dto.ParseRequestDto;
import com.orinuno.repository.EpisodeVariantRepository;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
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

    public Mono<List<KodikContent>> search(ParseRequestDto request) {
        KodikSearchRequest searchRequest =
                KodikSearchRequest.builder()
                        .title(request.getTitle())
                        .kinopoiskId(request.getKinopoiskId())
                        .imdbId(request.getImdbId())
                        .shikimoriId(request.getShikimoriId())
                        .build();

        return kodikApiClient
                .search(searchRequest)
                .flatMap(response -> processSearchResults(response, request.isDecodeLinks()));
    }

    private Mono<List<KodikContent>> processSearchResults(
            KodikSearchResponse response, boolean decodeLinks) {
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
                            if (decodeLinks) {
                                return decodeAllPending(contents).thenReturn(contents);
                            }
                            return Mono.just(contents);
                        });
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

    /** PF-I3: Scheduled task to refresh expired mp4 links. */
    @Scheduled(fixedDelayString = "${orinuno.decoder.refresh-interval-ms:3600000}")
    public void refreshExpiredLinks() {
        int ttlHours = properties.getDecoder().getLinkTtlHours();
        int batchSize = properties.getDecoder().getRefreshBatchSize();
        List<KodikEpisodeVariant> expired =
                episodeVariantRepository.findExpiredLinks(ttlHours, batchSize);

        if (expired.isEmpty()) {
            log.debug("✅ No expired mp4 links to refresh");
            return;
        }

        log.info("🔄 Refreshing {} expired mp4 links (TTL={}h)", expired.size(), ttlHours);

        Flux.fromIterable(expired)
                .delayElements(Duration.ofMillis(properties.getKodik().getRequestDelayMs()))
                .flatMap(variant -> decodeVariant(variant), 1)
                .then()
                .block();
    }

    /** PF-I4: Scheduled task to retry previously failed decodes. */
    @Scheduled(
            fixedDelayString = "${orinuno.decoder.refresh-interval-ms:3600000}",
            initialDelayString = "1800000")
    public void retryFailedDecodes() {
        int batchSize = properties.getDecoder().getRefreshBatchSize();
        List<KodikEpisodeVariant> failed = episodeVariantRepository.findFailedDecode(batchSize);

        if (failed.isEmpty()) {
            log.debug("✅ No failed decodes to retry");
            return;
        }

        log.info("🔁 Retrying {} previously failed decodes", failed.size());

        Flux.fromIterable(failed)
                .delayElements(Duration.ofMillis(properties.getKodik().getRequestDelayMs()))
                .flatMap(variant -> decodeVariant(variant), 1)
                .then()
                .block();
    }

    private Mono<Void> decodeAllPending(List<KodikContent> contents) {
        return Flux.fromIterable(contents)
                .flatMap(content -> decodeForContent(content.getId()), 1)
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
                            String bestLink = selectBestQuality(videoLinks);
                            if (bestLink != null) {
                                episodeVariantRepository.updateMp4Link(variant.getId(), bestLink);
                                log.info(
                                        "✅ Decoded variant id={}: {} qualities, best={}",
                                        variant.getId(),
                                        videoLinks.size(),
                                        bestLink.substring(0, Math.min(60, bestLink.length())));
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
                .map(Map.Entry::getValue)
                .orElse(null);
    }
}
