package com.orinuno.service.provider.aniboom;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 * PLAYER-2 (ADR 0006) — Aniboom decoder. Fetches the iframe HTML, extracts the {@code <input
 * id="video-data" data-parameters="<json-string>">} blob, parses out HLS / DASH URLs.
 *
 * <p>Aniboom's anti-hotlink check accepts {@code https://animego.org/} as a Referer.
 *
 * <p>Returns HLS as primary (DOWNLOAD-PARALLEL master-playlist resolver handles it transparently);
 * DASH appears as a secondary {@code "dash"} bucket so consumers can opt in.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AniboomDecoderService {

    static final Pattern VIDEO_DATA_INPUT =
            Pattern.compile(
                    "<input[^>]*id=\"video-data\"[^>]*data-parameters=\"([^\"]+)\"",
                    Pattern.CASE_INSENSITIVE);

    static final String REFERER = "https://animego.org/";

    private final WebClient.Builder webClientBuilder;
    private final RotatingUserAgentProvider userAgents;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public Mono<ProviderDecodeResult> decode(String embedUrl) {
        WebClient client =
                webClientBuilder
                        .defaultHeader("Referer", REFERER)
                        .defaultHeader("User-Agent", userAgents.stableDesktop())
                        .build();
        return client.get()
                .uri(embedUrl)
                .retrieve()
                .bodyToMono(String.class)
                .map(this::extractFromHtml)
                .onErrorResume(
                        ex -> {
                            log.warn(
                                    "PLAYER-2 Aniboom decode error for {}: {}",
                                    embedUrl,
                                    ex.toString());
                            return Mono.just(ProviderDecodeResult.failure("ANIBOOM_FETCH_ERROR"));
                        });
    }

    ProviderDecodeResult extractFromHtml(String html) {
        if (html == null || html.isBlank()) {
            return ProviderDecodeResult.failure("ANIBOOM_DATA_INPUT_MISSING");
        }
        Matcher matcher = VIDEO_DATA_INPUT.matcher(html);
        if (!matcher.find()) {
            return ProviderDecodeResult.failure("ANIBOOM_DATA_INPUT_MISSING");
        }
        String entityEncoded = matcher.group(1);
        String json = htmlEntityDecode(entityEncoded);
        if (json.isBlank() || json.equals("{}")) {
            return ProviderDecodeResult.failure("ANIBOOM_GEO_BLOCKED");
        }
        try {
            JsonNode node = objectMapper.readTree(json);
            return parseVideoData(node);
        } catch (Exception ex) {
            log.warn("PLAYER-2 Aniboom JSON parse failed: {}", ex.toString());
            return ProviderDecodeResult.failure("ANIBOOM_JSON_PARSE_ERROR");
        }
    }

    private ProviderDecodeResult parseVideoData(JsonNode node) {
        Map<String, String> qualities = new LinkedHashMap<>();
        String hls = textOrNull(node.path("hls"));
        if (hls != null) {
            qualities.put("auto", hls);
        }
        String dash = textOrNull(node.path("dash"));
        if (dash != null) {
            qualities.put("dash", dash);
        }
        if (qualities.isEmpty()) {
            return ProviderDecodeResult.failure("ANIBOOM_NO_PLAYLIST");
        }
        String format = hls != null ? "application/x-mpegURL" : "application/dash+xml";
        return ProviderDecodeResult.success(qualities, format);
    }

    private static String textOrNull(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        String value = node.asText("").trim();
        return value.isEmpty() ? null : value;
    }

    static String htmlEntityDecode(String encoded) {
        if (encoded == null) {
            return "";
        }
        return encoded.replace("&quot;", "\"")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&#39;", "'")
                .replace("&apos;", "'");
    }
}
