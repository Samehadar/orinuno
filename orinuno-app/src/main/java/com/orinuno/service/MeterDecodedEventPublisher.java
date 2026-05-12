/*
 * MeterDecodedEventPublisher — ADR 0021 §B2-decoded.
 *
 * After a successful Kodik decode, push a SourceCatalogEvent.VariantDecoded
 * to meter's /api/v1/source-events/decoded endpoint so meter is the sole
 * writer of episode_video. ADR 0021 §B1 retired the legacy in-process
 * KodikEpisodeDualWriteService; this publisher is now the only path from
 * an orinuno-app decode into the canonical L2 row.
 *
 * Transport: WebClient POST, fire-and-forget. Failures log WARN and are
 * swallowed — the decode itself already succeeded, the user-visible
 * stream URL is in `mp4Link`, and the next decode tick (DECODE-8 PF-I3
 * full re-decode) will re-emit. A future B2-decoded-outbox patch may
 * introduce a durable outbox + watermark on the orinuno-app side; the
 * wire contract stays unchanged.
 *
 * Gated by orinuno.meter.base-url — unset = bean missing = no publish.
 * In a monolith/dev profile without a reachable meter, episode_video stays
 * empty in orinuno_catalog; the user-visible stream URL still lands in
 * kodik_episode_variant.mp4_link from ParserService.
 */
package com.orinuno.service;

import com.kodik.decoder.DecodeAttemptResult;
import com.orinuno.contract.source.Provenance;
import com.orinuno.contract.source.SourceCatalogEvent;
import com.orinuno.contract.source.SourceIdentifier;
import com.orinuno.model.KodikEpisodeVariant;
import java.time.Clock;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

@Slf4j
@Component
@ConditionalOnProperty(prefix = "orinuno.meter", name = "base-url")
public class MeterDecodedEventPublisher {

    private static final String SOURCE_TYPE = "kodik";
    private static final ParameterizedTypeReference<List<SourceCatalogEvent>> EVENT_LIST_TYPE =
            new ParameterizedTypeReference<>() {};

    private final WebClient client;
    private final Clock clock;

    public MeterDecodedEventPublisher(
            WebClient.Builder builder,
            @Value("${orinuno.meter.base-url}") String baseUrl,
            Clock clock) {
        this.client = builder.baseUrl(baseUrl).build();
        this.clock = clock;
        log.info("MeterDecodedEventPublisher ENABLED — meter base-url={}", baseUrl);
    }

    public void publishDecoded(
            KodikEpisodeVariant variant,
            DecodeAttemptResult result,
            String pickedQuality,
            String pickedUrl) {
        if (variant == null
                || variant.getContentId() == null
                || variant.getSeasonNumber() == null
                || variant.getEpisodeNumber() == null
                || variant.getId() == null
                || pickedQuality == null
                || pickedUrl == null) {
            return;
        }
        String decodeMethod =
                result != null && result.method() != null ? result.method().name() : null;
        SourceCatalogEvent.VariantDecoded event =
                new SourceCatalogEvent.VariantDecoded(
                        SourceIdentifier.of(SOURCE_TYPE, String.valueOf(variant.getContentId())),
                        variant.getSeasonNumber(),
                        variant.getEpisodeNumber(),
                        SourceIdentifier.of(SOURCE_TYPE, String.valueOf(variant.getId())),
                        pickedUrl,
                        pickedQuality,
                        decodeMethod,
                        null,
                        Provenance.of(
                                variant.getKodikLink() != null
                                        ? variant.getKodikLink()
                                        : "kodik://variant/" + variant.getId(),
                                clock.instant()));
        client.post()
                .uri("/api/v1/source-events/decoded")
                // Use BodyInserters with the typed reference so Jackson sees the static type as
                // List<SourceCatalogEvent> and writes the @JsonTypeInfo "kind" discriminator.
                // .bodyValue(List.of(event)) drops the discriminator because the runtime type
                // resolves to the concrete subtype, not the sealed interface.
                .body(BodyInserters.fromPublisher(Mono.just(List.of(event)), EVENT_LIST_TYPE))
                .retrieve()
                .toBodilessEntity()
                .doOnError(
                        e ->
                                log.warn(
                                        "meter publish failed for variant id={} ({}): the next"
                                                + " decode tick will re-emit; until then the L2"
                                                + " row for this variant stays whatever meter saw"
                                                + " last",
                                        variant.getId(),
                                        e.toString()))
                .subscribe();
    }
}
