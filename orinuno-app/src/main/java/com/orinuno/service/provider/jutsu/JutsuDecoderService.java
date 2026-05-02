package com.orinuno.service.provider.jutsu;

import com.orinuno.client.http.RotatingUserAgentProvider;
import com.orinuno.service.provider.ProviderDecodeResult;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * PLAYER-4 (ADR 0009) — JutSu decoder. Fetches the episode page HTML, extracts every {@code <source
 * src="..." type="video/mp4">} tag and groups by quality based on the {@code label} or the URL path
 * (jut.su URLs encode the quality as {@code 720.mp4} / {@code 1080.mp4}).
 *
 * <p>Premium gated episodes are detected via a marker in the HTML and reported as a permanent
 * failure (no retry, decoder doesn't have user credentials). Cloudflare challenges (HTTP 403 with
 * cf challenge body) are reported as transient — the orchestrator may retry from a different
 * egress.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class JutsuDecoderService {

    static final Pattern SOURCE_TAG =
            Pattern.compile(
                    "<source[^>]*src=\"([^\"]+\\.mp4[^\"]*)\"[^>]*(?:label=\"([^\"]+)\")?",
                    Pattern.CASE_INSENSITIVE);

    static final Pattern QUALITY_FROM_URL =
            Pattern.compile("/(\\d{3,4})(?:p)?\\.mp4", Pattern.CASE_INSENSITIVE);

    static final Pattern PREMIUM_MARKER =
            Pattern.compile(
                    "(?:premium|только для премиум|подписк[аиу])",
                    Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private final WebClient.Builder webClientBuilder;
    private final RotatingUserAgentProvider userAgents;

    public Mono<ProviderDecodeResult> decode(String episodeUrl) {
        WebClient client =
                webClientBuilder.defaultHeader("User-Agent", userAgents.stableDesktop()).build();
        return client.get()
                .uri(episodeUrl)
                .retrieve()
                .bodyToMono(String.class)
                .map(JutsuDecoderService::extractFromHtml)
                .onErrorResume(
                        ex -> {
                            log.warn(
                                    "PLAYER-4 JutSu decode error for {}: {}",
                                    episodeUrl,
                                    ex.toString());
                            return Mono.just(ProviderDecodeResult.failure("JUTSU_FETCH_ERROR"));
                        });
    }

    static ProviderDecodeResult extractFromHtml(String html) {
        if (html == null || html.isBlank()) {
            return ProviderDecodeResult.failure("JUTSU_EMPTY_RESPONSE");
        }
        if (looksLikeCloudflareChallenge(html)) {
            return ProviderDecodeResult.failure("JUTSU_CLOUDFLARE_BLOCKED");
        }
        Map<String, String> qualities = new LinkedHashMap<>();
        Matcher m = SOURCE_TAG.matcher(html);
        while (m.find()) {
            String url = m.group(1).trim();
            String label = m.group(2);
            String quality = pickQuality(label, url);
            qualities.putIfAbsent(quality, url);
        }
        if (qualities.isEmpty()) {
            if (PREMIUM_MARKER.matcher(html).find()) {
                return ProviderDecodeResult.failure("JUTSU_PREMIUM_REQUIRED");
            }
            return ProviderDecodeResult.failure("JUTSU_SOURCE_TAG_MISSING");
        }
        return ProviderDecodeResult.success(qualities, "video/mp4");
    }

    static String pickQuality(String label, String url) {
        if (label != null && !label.isBlank()) {
            String stripped = label.trim().replaceAll("(?i)p$", "");
            if (stripped.matches("\\d{3,4}")) {
                return stripped;
            }
        }
        Matcher m = QUALITY_FROM_URL.matcher(url);
        if (m.find()) {
            return m.group(1);
        }
        return "auto";
    }

    static boolean looksLikeCloudflareChallenge(String html) {
        return html.contains("Just a moment...")
                || html.contains("cf-browser-verification")
                || html.contains("__cf_chl_jschl_tk__");
    }
}
