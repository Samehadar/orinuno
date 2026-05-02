package com.orinuno.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.orinuno.configuration.OrinunoProperties;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

@Slf4j
@Service
@RequiredArgsConstructor
public class KodikVideoDecoderService {

    private static final Pattern URL_PARAMS_PATTERN = Pattern.compile("var urlParams = '([^']*)'");
    private static final Pattern TYPE_PATTERN = Pattern.compile("vInfo\\.type = '([^']+)'");
    private static final Pattern HASH_PATTERN = Pattern.compile("vInfo\\.hash = '([^']+)'");
    private static final Pattern ID_PATTERN = Pattern.compile("vInfo\\.id = '([^']+)'");
    private static final Pattern PLAYER_JS_PATTERN =
            Pattern.compile("src=\"/(assets/js/app\\.player_[^\"]+\\.js)\"");
    private static final Pattern POST_URL_PATTERN =
            Pattern.compile("type:\"POST\",url:atob\\(\"([^\"]+)\"\\)");
    private static final Pattern VIDEO_SRC_PATTERN =
            Pattern.compile("\"([0-9]+)p?\":\\[\\{\"src\":\"([^\"]+)");

    private static final AtomicInteger cachedShift = new AtomicInteger(18);
    private static final java.util.concurrent.atomic.AtomicReference<String> cachedVideoInfoPath =
            new java.util.concurrent.atomic.AtomicReference<>(null);
    private static final String[] KNOWN_VIDEO_INFO_PATHS = {"/ftor", "/kor", "/gvi", "/seria"};

    private final WebClient kodikPlayerWebClient;
    private final ProxyWebClientService proxyWebClientService;
    private final DecoderHealthTracker healthTracker;
    private final GeoBlockDetector geoBlockDetector;
    private final OrinunoProperties properties;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Decodes a Kodik player link into direct MP4/HLS URLs. Returns a map of quality -> decoded
     * URL.
     */
    public Mono<Map<String, String>> decode(String kodikLink) {
        String fullUrl = normalizeUrl(kodikLink);
        log.info("🎬 [Step 1/8] Starting decode for: {}", fullUrl);

        return loadIframePage(fullUrl)
                .flatMap(iframeHtml -> processIframe(fullUrl, iframeHtml))
                .doOnSuccess(
                        result -> {
                            healthTracker.recordSuccess();
                            log.info("✅ Decode complete: {} qualities found", result.size());
                        })
                .doOnError(
                        error -> {
                            healthTracker.recordFailure("unknown", error.getMessage());
                            log.error("❌ Decode failed for {}: {}", kodikLink, error.getMessage());
                        })
                .timeout(Duration.ofSeconds(properties.getDecoder().getTimeoutSeconds()));
    }

    private Mono<String> loadIframePage(String url) {
        log.debug("[Step 2/8] Loading iframe page: {}", url);
        return proxyWebClientService
                .executeWithProxyFallback(
                        kodikPlayerWebClient,
                        client ->
                                client.get()
                                        .uri(url)
                                        .header("User-Agent", randomUserAgent())
                                        .retrieve()
                                        .bodyToMono(String.class))
                .doOnError(e -> healthTracker.recordFailure("step2_load_iframe", e.getMessage()));
    }

    private Mono<Map<String, String>> processIframe(String baseUrl, String iframeHtml) {
        log.debug("🔎 [Step 3/8] Extracting params from iframe");

        String urlParamsJson = extractMatch(URL_PARAMS_PATTERN, iframeHtml);
        String type = extractMatch(TYPE_PATTERN, iframeHtml);
        String hash = extractMatch(HASH_PATTERN, iframeHtml);
        String id = extractMatch(ID_PATTERN, iframeHtml);

        if (type == null || hash == null || id == null) {
            healthTracker.recordFailure("step3_extract_params", "Missing type/hash/id from iframe");
            return Mono.error(new RuntimeException("Failed to extract vInfo params from iframe"));
        }

        String playerJsPath = extractMatch(PLAYER_JS_PATTERN, iframeHtml);
        if (playerJsPath == null) {
            healthTracker.recordFailure("step3_extract_player_js", "Player JS path not found");
            return Mono.error(new RuntimeException("Player JS path not found in iframe"));
        }

        String domain = extractDomain(baseUrl);
        String playerJsUrl = String.format("https://%s/%s", domain, playerJsPath);

        log.debug("📜 [Step 4/8] Loading player JS: {}", playerJsUrl);

        return loadPlayerJs(playerJsUrl)
                .flatMap(
                        playerJs -> {
                            log.debug("[Step 5/8] Extracting POST URL from player JS");
                            String videoInfoPath = resolveVideoInfoPath(playerJs);
                            String postUrl = String.format("https://%s%s", domain, videoInfoPath);
                            log.debug("[Step 6/8] Sending POST to: {}", postUrl);

                            return sendVideoRequest(postUrl, urlParamsJson, type, hash, id)
                                    .flatMap(
                                            result -> {
                                                if (result.isEmpty()) {
                                                    return tryFallbackPaths(
                                                            domain,
                                                            urlParamsJson,
                                                            type,
                                                            hash,
                                                            id,
                                                            videoInfoPath);
                                                }
                                                cachedVideoInfoPath.set(videoInfoPath);
                                                return Mono.just(result);
                                            });
                        });
    }

    private String resolveVideoInfoPath(String playerJs) {
        String encodedPostPath = extractMatch(POST_URL_PATTERN, playerJs);
        if (encodedPostPath != null) {
            try {
                String decoded = decodeBase64Standard(encodedPostPath);
                if (decoded.startsWith("/")) {
                    cachedVideoInfoPath.set(decoded);
                    return decoded;
                }
            } catch (Exception e) {
                log.warn("Failed to decode video-info path from player JS: {}", e.getMessage());
            }
        }

        String cached = cachedVideoInfoPath.get();
        if (cached != null) {
            log.info("Using cached video-info path: {}", cached);
            return cached;
        }

        log.warn("Video-info path not found in player JS, using default fallback: /ftor");
        return KNOWN_VIDEO_INFO_PATHS[0];
    }

    private Mono<Map<String, String>> tryFallbackPaths(
            String domain,
            String urlParamsJson,
            String type,
            String hash,
            String id,
            String alreadyTried) {
        Mono<Map<String, String>> chain = Mono.just(Map.<String, String>of());

        for (String path : KNOWN_VIDEO_INFO_PATHS) {
            if (path.equals(alreadyTried)) continue;
            final String postUrl = String.format("https://%s%s", domain, path);
            chain =
                    chain.flatMap(
                            prev -> {
                                if (!prev.isEmpty()) return Mono.just(prev);
                                log.info("Trying fallback video-info path: {}", path);
                                return sendVideoRequest(postUrl, urlParamsJson, type, hash, id)
                                        .map(
                                                result -> {
                                                    if (!result.isEmpty()) {
                                                        cachedVideoInfoPath.set(path);
                                                        log.info(
                                                                "Fallback path succeeded: {}",
                                                                path);
                                                    }
                                                    return result;
                                                })
                                        .onErrorResume(
                                                e -> {
                                                    log.debug(
                                                            "Fallback path {} failed: {}",
                                                            path,
                                                            e.getMessage());
                                                    return Mono.just(Map.of());
                                                });
                            });
        }

        return chain;
    }

    private Mono<String> loadPlayerJs(String url) {
        return proxyWebClientService
                .executeWithProxyFallback(
                        kodikPlayerWebClient,
                        client ->
                                client.get()
                                        .uri(url)
                                        .header("User-Agent", randomUserAgent())
                                        .retrieve()
                                        .bodyToMono(String.class))
                .doOnError(
                        e -> healthTracker.recordFailure("step4_load_player_js", e.getMessage()));
    }

    private Mono<Map<String, String>> sendVideoRequest(
            String postUrl, String urlParamsJson, String type, String hash, String id) {
        MultiValueMap<String, String> formData = new LinkedMultiValueMap<>();

        if (urlParamsJson != null) {
            try {
                JsonNode params = objectMapper.readTree(urlParamsJson);
                params.fields()
                        .forEachRemaining(
                                field -> formData.add(field.getKey(), field.getValue().asText()));
            } catch (Exception e) {
                log.warn(
                        "⚠️ Failed to parse urlParams JSON, continuing without: {}",
                        e.getMessage());
            }
        }

        formData.add("bad_user", "false");
        formData.add("cdn_is_working", "true");
        formData.add("type", type);
        formData.add("hash", hash);
        formData.add("id", id);
        formData.add("info", "{}");

        return proxyWebClientService
                .executeWithProxyFallback(
                        kodikPlayerWebClient,
                        client ->
                                client.post()
                                        .uri(postUrl)
                                        .header("User-Agent", randomUserAgent())
                                        .header("X-Requested-With", "XMLHttpRequest")
                                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                                        .body(BodyInserters.fromFormData(formData))
                                        .retrieve()
                                        .bodyToMono(String.class))
                .map(this::parseVideoResponse)
                .doOnError(e -> healthTracker.recordFailure("step6_post_request", e.getMessage()));
    }

    Map<String, String> parseVideoResponse(String responseJson) {
        log.debug("[Step 7/8] Parsing video response");
        Map<String, String> videoLinks = new HashMap<>();
        boolean geoBlocked = false;

        Matcher matcher = VIDEO_SRC_PATTERN.matcher(responseJson);
        while (matcher.find()) {
            String quality = matcher.group(1);
            String encodedSrc = matcher.group(2);
            String decodedUrl = decodeVideoUrl(encodedSrc);

            if (geoBlockDetector.isCdnGeoBlocked(decodedUrl)) {
                geoBlocked = true;
            }

            videoLinks.put(quality, decodedUrl);
        }

        if (geoBlocked) {
            log.warn(
                    "[Step 8/8] All {} decoded URLs are geo-blocked (kodik edge proxy detected),"
                            + " returning empty result",
                    videoLinks.size());
            return new HashMap<>();
        }

        log.debug("[Step 8/8] Decoded {} video links", videoLinks.size());
        return videoLinks;
    }

    static String decodeVideoUrl(String encoded) {
        if (encoded.contains("manifest.m3u8")) {
            return normalizeDecodedUrl(encoded);
        }

        int currentShift = cachedShift.get();
        String result = tryDecodeWithShift(encoded, currentShift);
        if (result != null) return result;

        for (int shift = 0; shift < 26; shift++) {
            if (shift == currentShift) continue;
            result = tryDecodeWithShift(encoded, shift);
            if (result != null) {
                log.info("ROT cipher shift changed: {} -> {}", currentShift, shift);
                cachedShift.set(shift);
                return result;
            }
        }

        log.warn("Brute-force decode failed for all 26 shifts, using shift={}", currentShift);
        String fallback = decodeUrlSafeBase64(rotWithShift(encoded, currentShift));
        return normalizeDecodedUrl(fallback);
    }

    private static String tryDecodeWithShift(String encoded, int shift) {
        try {
            String rotated = rotWithShift(encoded, shift);
            String decoded = decodeUrlSafeBase64(rotated);
            if (decoded.contains("//")
                    && (decoded.contains(".mp4")
                            || decoded.contains(".m3u8")
                            || decoded.contains("/video/"))) {
                return normalizeDecodedUrl(decoded);
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    static String rotWithShift(String input, int shift) {
        StringBuilder result = new StringBuilder();
        for (char c : input.toCharArray()) {
            if (Character.isLetter(c)) {
                char base = Character.isUpperCase(c) ? 'A' : 'a';
                result.append((char) (base + (c - base + shift) % 26));
            } else {
                result.append(c);
            }
        }
        return result.toString();
    }

    static String decodeUrlSafeBase64(String encoded) {
        String standard = encoded.replace('-', '+').replace('_', '/');
        int padding = (4 - standard.length() % 4) % 4;
        standard += "=".repeat(padding);
        return new String(Base64.getDecoder().decode(standard), StandardCharsets.UTF_8);
    }

    private static String decodeBase64Standard(String encoded) {
        return new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8);
    }

    private static String normalizeDecodedUrl(String url) {
        if (url.startsWith("//")) {
            url = "https:" + url;
        }
        url = url.replace(":hls:manifest.m3u8", "").replace(":hls:hls.m3u8", "");
        return url;
    }

    private static String normalizeUrl(String kodikLink) {
        if (kodikLink.startsWith("//")) {
            return "https:" + kodikLink;
        }
        if (!kodikLink.startsWith("http")) {
            return "https:" + kodikLink;
        }
        return kodikLink;
    }

    private static String extractDomain(String url) {
        try {
            URI uri = URI.create(url);
            return uri.getHost();
        } catch (Exception e) {
            int start = url.indexOf("//");
            start = (start != -1) ? start + 2 : 0;
            int end = url.indexOf('/', start);
            return (end != -1) ? url.substring(start, end) : url.substring(start);
        }
    }

    private static String extractMatch(Pattern pattern, String content) {
        Matcher matcher = pattern.matcher(content);
        return matcher.find() ? matcher.group(1) : null;
    }

    private static String randomUserAgent() {
        String[] agents = {
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko)"
                    + " Chrome/135.0.0.0 Safari/537.36",
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko)"
                    + " Chrome/135.0.0.0 Safari/537.36",
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko)"
                    + " Chrome/135.0.0.0 Safari/537.36",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:128.0) Gecko/20100101 Firefox/128.0",
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10.15; rv:128.0) Gecko/20100101 Firefox/128.0"
        };
        return agents[(int) (Math.random() * agents.length)];
    }
}
