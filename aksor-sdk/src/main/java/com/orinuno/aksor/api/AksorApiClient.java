package com.orinuno.aksor.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.orinuno.aksor.AksorConfig;
import com.orinuno.aksor.AksorErrorCodes;
import com.orinuno.aksor.AksorException;
import com.orinuno.aksor.drift.AksorDriftDetector;
import com.orinuno.aksor.drift.AksorDriftSignal;
import com.orinuno.aksor.model.AksorVideoQualities;
import com.orinuno.aksor.parser.AksorHashParser;
import jakarta.annotation.Nullable;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * Talks to {@code https://player.aksor.tv/api/video/{hash}}. The endpoint is open but gates by
 * Referer (publisher whitelist) — callers must pass the host-page referer or fall back to {@link
 * AksorConfig#referer()}.
 */
@Slf4j
public final class AksorApiClient {

    private final AksorConfig config;
    private final WebClient client;
    private final AksorDriftDetector drift;

    public AksorApiClient(AksorConfig config, WebClient.Builder webClientBuilder) {
        this(config, webClientBuilder, AksorDriftDetector.disabled());
    }

    public AksorApiClient(
            AksorConfig config, WebClient.Builder webClientBuilder, AksorDriftDetector drift) {
        this.config = config;
        this.client =
                webClientBuilder
                        .baseUrl(config.apiBaseUrl())
                        .defaultHeader("User-Agent", config.userAgent())
                        .defaultHeader("Accept", "application/json, text/plain, */*")
                        .build();
        this.drift = drift == null ? AksorDriftDetector.disabled() : drift;
    }

    public Mono<AksorVideoQualities> getQualities(String hash) {
        return getQualities(hash, null);
    }

    public Mono<AksorVideoQualities> getQualities(String hash, @Nullable String refererOverride) {
        if (!AksorHashParser.looksLikeHash(hash)) {
            return Mono.error(
                    new AksorException(
                            AksorErrorCodes.AKSOR_API_ERROR,
                            "hash must be 32 hex chars, got: " + hash));
        }
        String path = "/api/video/" + URLEncoder.encode(hash, StandardCharsets.UTF_8);
        String referer =
                refererOverride != null && !refererOverride.isBlank()
                        ? refererOverride
                        : config.referer();
        return client.get()
                .uri(path)
                .headers(
                        h -> {
                            h.set("Referer", referer);
                            h.set("Origin", stripTrailingSlash(config.playerBaseUrl()));
                        })
                .retrieve()
                .bodyToMono(JsonNode.class)
                .map(body -> mapQualities(body, hash, drift))
                .onErrorResume(
                        ex -> {
                            if (ex instanceof AksorException) {
                                return Mono.error(ex);
                            }
                            log.warn("Aksor video fetch failed hash={}: {}", hash, ex.toString());
                            return Mono.error(
                                    new AksorException(
                                            AksorErrorCodes.AKSOR_API_ERROR, ex.toString(), ex));
                        });
    }

    static AksorVideoQualities mapQualities(JsonNode body, String hash, AksorDriftDetector drift) {
        if (body == null) {
            throw new AksorException(AksorErrorCodes.AKSOR_API_ERROR, "empty body");
        }
        JsonNode q = body.path("qualities");
        if (q.isMissingNode() || q.isNull()) {
            drift.record(AksorDriftSignal.AKSOR_QUALITIES_MISSING, Map.of("hash", hash));
            throw new AksorException(AksorErrorCodes.AKSOR_NO_QUALITIES, "no qualities node");
        }
        AksorVideoQualities qualities =
                new AksorVideoQualities(
                        text(q.path("q1080")),
                        text(q.path("q720")),
                        text(q.path("q480")),
                        text(q.path("q360")),
                        text(q.path("q2k")),
                        text(q.path("q4k")));
        if (qualities.isEmpty()) {
            drift.record(AksorDriftSignal.AKSOR_QUALITIES_ALL_NULL, Map.of("hash", hash));
            throw new AksorException(AksorErrorCodes.AKSOR_NO_QUALITIES, "all quality slots blank");
        }
        return qualities;
    }

    @Nullable
    private static String text(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        String v = node.asText("").trim();
        return v.isEmpty() ? null : v;
    }

    private static String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
