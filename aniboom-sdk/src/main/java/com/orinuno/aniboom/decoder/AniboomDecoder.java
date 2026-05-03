package com.orinuno.aniboom.decoder;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.orinuno.aniboom.AniboomConfig;
import com.orinuno.aniboom.AniboomDecodeResult;
import com.orinuno.aniboom.AniboomErrorCodes;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * Aniboom decoder. Fetches the iframe HTML, extracts the {@code <input id="video-data"
 * data-parameters="<json-string>">} blob, parses out HLS / DASH URLs.
 *
 * <p>Aniboom's anti-hotlink check accepts {@code https://animego.org/} as a Referer (configured via
 * {@link AniboomConfig#referer()}).
 *
 * <p>Returns HLS as primary (any downstream master-playlist resolver handles it transparently);
 * DASH appears as a secondary {@code "dash"} bucket so consumers can opt in. Both URLs are stable
 * for the duration of the embed page; refresh the embed if either 403s.
 */
@Slf4j
public final class AniboomDecoder {

    static final Pattern VIDEO_DATA_INPUT =
            Pattern.compile(
                    "<input[^>]*id=\"video-data\"[^>]*data-parameters=\"([^\"]+)\"",
                    Pattern.CASE_INSENSITIVE);

    private final WebClient client;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public AniboomDecoder(AniboomConfig config, WebClient.Builder webClientBuilder) {
        this.client =
                webClientBuilder
                        .defaultHeader("Referer", config.referer())
                        .defaultHeader("User-Agent", config.userAgent())
                        .build();
    }

    public Mono<AniboomDecodeResult> decode(String embedUrl) {
        return client.get()
                .uri(embedUrl)
                .retrieve()
                .bodyToMono(String.class)
                .map(this::extractFromHtml)
                .onErrorResume(
                        ex -> {
                            log.warn("Aniboom decode error for {}: {}", embedUrl, ex.toString());
                            return Mono.just(
                                    AniboomDecodeResult.failure(
                                            AniboomErrorCodes.ANIBOOM_FETCH_ERROR));
                        });
    }

    public AniboomDecodeResult extractFromHtml(String html) {
        if (html == null || html.isBlank()) {
            return AniboomDecodeResult.failure(AniboomErrorCodes.ANIBOOM_DATA_INPUT_MISSING);
        }
        Matcher matcher = VIDEO_DATA_INPUT.matcher(html);
        if (!matcher.find()) {
            return AniboomDecodeResult.failure(AniboomErrorCodes.ANIBOOM_DATA_INPUT_MISSING);
        }
        String entityEncoded = matcher.group(1);
        String json = htmlEntityDecode(entityEncoded);
        if (json.isBlank() || json.equals("{}")) {
            return AniboomDecodeResult.failure(AniboomErrorCodes.ANIBOOM_GEO_BLOCKED);
        }
        try {
            JsonNode node = objectMapper.readTree(json);
            return parseVideoData(node);
        } catch (Exception ex) {
            log.warn("Aniboom JSON parse failed: {}", ex.toString());
            return AniboomDecodeResult.failure(AniboomErrorCodes.ANIBOOM_JSON_PARSE_ERROR);
        }
    }

    private AniboomDecodeResult parseVideoData(JsonNode node) {
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
            return AniboomDecodeResult.failure(AniboomErrorCodes.ANIBOOM_NO_PLAYLIST);
        }
        String format = hls != null ? "application/x-mpegURL" : "application/dash+xml";
        return AniboomDecodeResult.success(qualities, format);
    }

    private static String textOrNull(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        String value = node.asText("").trim();
        return value.isEmpty() ? null : value;
    }

    public static String htmlEntityDecode(String encoded) {
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
