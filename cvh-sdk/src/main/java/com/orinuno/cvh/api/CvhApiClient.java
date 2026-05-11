package com.orinuno.cvh.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.orinuno.cvh.CvhConfig;
import com.orinuno.cvh.CvhErrorCodes;
import com.orinuno.cvh.model.CvhVideoSources;
import com.orinuno.cvh.model.CvhVoiceTrack;
import com.orinuno.cvh.parser.CvhUrlParser;
import jakarta.annotation.Nullable;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * Two-hop CVH plapi client.
 *
 * <ol>
 *   <li>{@code GET /api/v1/player/sv/playlist?pub={publisherId}&aggr={aggregator}&id={titleId}} —
 *       list voice tracks for one title
 *   <li>{@code GET /api/v1/player/sv/video/{vkId}} — resolve signed CDN URLs
 * </ol>
 *
 * <p>Both endpoints are public but CORS-gated: the {@code Referer} header must match the
 * publisher's whitelisted host page (e.g. {@code https://jut-su.works/}). Each method accepts an
 * optional per-call referer override; if absent, falls back to {@link CvhConfig#referer()}.
 */
@Slf4j
public final class CvhApiClient {

    private final CvhConfig config;
    private final WebClient client;

    public CvhApiClient(CvhConfig config, WebClient.Builder webClientBuilder) {
        this.config = config;
        this.client =
                webClientBuilder
                        .baseUrl(config.plapiBaseUrl())
                        .defaultHeader("User-Agent", config.userAgent())
                        .defaultHeader("Accept", "application/json, text/plain, */*")
                        .build();
    }

    public Mono<List<CvhVoiceTrack>> getTitleVoiceTracks(
            String titleId, String publisherId, String aggregator) {
        return getTitleVoiceTracks(titleId, publisherId, aggregator, null);
    }

    public Mono<List<CvhVoiceTrack>> getTitleVoiceTracks(
            String titleId,
            String publisherId,
            String aggregator,
            @Nullable String refererOverride) {
        String effectiveAggregator =
                (aggregator == null || aggregator.isBlank())
                        ? config.defaultAggregator()
                        : aggregator;
        StringBuilder path = new StringBuilder("/api/v1/player/sv/playlist?id=");
        path.append(URLEncoder.encode(titleId, StandardCharsets.UTF_8));
        path.append("&aggr=")
                .append(URLEncoder.encode(effectiveAggregator, StandardCharsets.UTF_8));
        if (publisherId != null && !publisherId.isBlank()) {
            path.append("&pub=").append(URLEncoder.encode(publisherId, StandardCharsets.UTF_8));
        }
        return client.get()
                .uri(path.toString())
                .headers(h -> h.set("Referer", effectiveReferer(refererOverride)))
                .retrieve()
                .bodyToMono(JsonNode.class)
                .map(CvhApiClient::mapTracks)
                .defaultIfEmpty(List.of())
                .onErrorResume(
                        ex -> {
                            log.warn(
                                    "CVH title fetch failed titleId={} publisherId={}: {}",
                                    titleId,
                                    publisherId,
                                    ex.toString());
                            return Mono.error(new CvhApiException(CvhErrorCodes.CVH_API_ERROR, ex));
                        });
    }

    public Mono<CvhVideoSources> getVideoSources(String vkId) {
        return getVideoSources(vkId, null);
    }

    public Mono<CvhVideoSources> getVideoSources(String vkId, @Nullable String refererOverride) {
        if (vkId == null || vkId.isBlank()) {
            return Mono.error(new CvhApiException(CvhErrorCodes.CVH_VIDEO_NOT_FOUND, "empty vkId"));
        }
        String path = "/api/v1/player/sv/video/" + URLEncoder.encode(vkId, StandardCharsets.UTF_8);
        return client.get()
                .uri(path)
                .headers(h -> h.set("Referer", effectiveReferer(refererOverride)))
                .retrieve()
                .bodyToMono(JsonNode.class)
                .map(CvhApiClient::mapSources)
                .onErrorResume(
                        ex -> {
                            if (ex instanceof CvhApiException) {
                                return Mono.error(ex);
                            }
                            log.warn("CVH video fetch failed vkId={}: {}", vkId, ex.toString());
                            return Mono.error(new CvhApiException(CvhErrorCodes.CVH_API_ERROR, ex));
                        });
    }

    private String effectiveReferer(@Nullable String override) {
        return (override == null || override.isBlank()) ? config.referer() : override;
    }

    private static List<CvhVoiceTrack> mapTracks(JsonNode body) {
        if (body == null) {
            return List.of();
        }
        JsonNode items = body.path("items");
        if (!items.isArray()) {
            return List.of();
        }
        List<CvhVoiceTrack> out = new ArrayList<>(items.size());
        for (JsonNode item : items) {
            out.add(
                    new CvhVoiceTrack(
                            asText(item.path("cvhId")),
                            asText(item.path("vkId")),
                            asText(item.path("voiceStudio")),
                            asText(item.path("voiceType"))));
        }
        return out;
    }

    private static CvhVideoSources mapSources(JsonNode body) {
        if (body == null) {
            throw new CvhApiException(CvhErrorCodes.CVH_VIDEO_NOT_FOUND, "empty body");
        }
        JsonNode sources = body.path("sources");
        if (sources.isMissingNode()) {
            throw new CvhApiException(CvhErrorCodes.CVH_VIDEO_NOT_FOUND, "no sources node");
        }
        String hls = asText(sources.path("hlsUrl"));
        return new CvhVideoSources(
                body.path("unitedVideoId").isNumber() ? body.path("unitedVideoId").asLong() : null,
                body.path("duration").isNumber() ? body.path("duration").asInt() : null,
                asText(body.path("thumbUrl")),
                hls,
                asText(sources.path("dashUrl")),
                asText(sources.path("mpegFullHdUrl")),
                asText(sources.path("mpegHighUrl")),
                asText(sources.path("mpegMediumUrl")),
                asText(sources.path("mpegLowUrl")),
                asText(sources.path("mpegLowestUrl")),
                asText(sources.path("mpegTinyUrl")),
                CvhUrlParser.parseExpiresFromUrl(hls).orElse(null));
    }

    private static String asText(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        String v = node.asText("").trim();
        return v.isEmpty() ? null : v;
    }
}
