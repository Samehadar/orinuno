package com.orinuno.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.orinuno.configuration.OrinunoProperties;
import com.orinuno.service.metrics.KodikDecoderMetrics;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
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

    /**
     * Matches the player JavaScript file referenced inside the iframe HTML.
     *
     * <p>Kodik changed the naming scheme around 2026-05: in addition to the legacy {@code
     * app.player_<variant>.<sha>.js} (e.g. {@code app.player_single.<sha>.js} for movies), serial
     * iframes now reference {@code app.serial.<sha>.js}. The original regex {@code
     * app\.player_[^.]+\.js} did NOT match the serial naming, silently breaking decode for every
     * serial since the change. See docs/quirks-and-hacks.md "Player JS file naming is now
     * type-dependent" entry.
     *
     * <p>The regex now accepts any {@code app.<word>.<...>.js} under {@code /assets/js/} so future
     * naming variants (e.g. {@code app.player.*}, {@code app.player_inline.*}) keep working without
     * another emergency patch.
     */
    private static final Pattern PLAYER_JS_PATTERN =
            Pattern.compile("src=\"/(assets/js/app\\.[a-zA-Z0-9_]+\\.[A-Za-z0-9]+\\.js)\"");

    private static final Pattern POST_URL_PATTERN =
            Pattern.compile("type:\"POST\",url:atob\\(\"([^\"]+)\"\\)");
    private static final Pattern VIDEO_SRC_PATTERN =
            Pattern.compile("\"([0-9]+)p?\":\\[\\{\"src\":\"([^\"]+)");

    private static final AtomicInteger cachedShift = new AtomicInteger(18);

    /**
     * Per-netloc cache of the discovered video-info POST path.
     *
     * <p>DECODE-7 (per-netloc cache): the previous {@code AtomicReference<String>} was a single
     * global cache, which caused the path to flap between netlocs whenever orinuno decoded iframes
     * from {@code kodikplayer.com}, {@code kodik.cc}, {@code kodikv.cc}, or any other netloc that
     * uses a different player JS distribution and possibly a different POST path. Today both {@code
     * kodikplayer.com} and {@code kodik.cc} resolve to {@code /ftor}, but Kodik has form for
     * differentiating them (see docs/quirks-and-hacks.md, "Player JS file naming is now
     * type-dependent" entry — analogous risk).
     *
     * <p>Keyed by the host (no protocol, no port) of the iframe URL, lower-cased. We intentionally
     * do NOT eagerly populate it from {@link #KNOWN_VIDEO_INFO_PATHS}; entries appear lazily on
     * first successful POST per netloc.
     */
    private static final ConcurrentMap<String, String> cachedVideoInfoPathByNetloc =
            new ConcurrentHashMap<>();

    private static final String[] KNOWN_VIDEO_INFO_PATHS = {"/ftor", "/kor", "/gvi", "/seria"};

    private final WebClient kodikPlayerWebClient;
    private final ProxyWebClientService proxyWebClientService;
    private final DecoderHealthTracker healthTracker;
    private final GeoBlockDetector geoBlockDetector;
    private final OrinunoProperties properties;
    private final KodikDecoderMetrics decoderMetrics;
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
                            if (decoderMetrics != null) {
                                decoderMetrics.recordOutcome(
                                        result.isEmpty()
                                                ? com.orinuno.service.metrics.KodikDecoderMetrics
                                                        .DecodeOutcome.EMPTY_LINKS
                                                : com.orinuno.service.metrics.KodikDecoderMetrics
                                                        .DecodeOutcome.SUCCESS);
                            }
                            log.info("✅ Decode complete: {} qualities found", result.size());
                        })
                .doOnError(
                        error -> {
                            healthTracker.recordFailure("unknown", error.getMessage());
                            if (decoderMetrics != null) {
                                decoderMetrics.recordOutcome(
                                        com.orinuno.service.metrics.KodikDecoderMetrics
                                                .DecodeOutcome.UPSTREAM_ERROR);
                            }
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
                            String videoInfoPath = resolveVideoInfoPath(domain, playerJs);
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
                                                cacheVideoInfoPath(domain, videoInfoPath);
                                                return Mono.just(result);
                                            });
                        });
    }

    private String resolveVideoInfoPath(String netloc, String playerJs) {
        String encodedPostPath = extractMatch(POST_URL_PATTERN, playerJs);
        if (encodedPostPath != null) {
            try {
                String decoded = decodeBase64Standard(encodedPostPath);
                if (decoded.startsWith("/")) {
                    cacheVideoInfoPath(netloc, decoded);
                    return decoded;
                }
            } catch (Exception e) {
                log.warn("Failed to decode video-info path from player JS: {}", e.getMessage());
            }
        }

        String cached = cachedVideoInfoPathByNetloc.get(netlocKey(netloc));
        if (cached != null) {
            log.info("Using cached video-info path for netloc {}: {}", netloc, cached);
            return cached;
        }

        log.warn(
                "Video-info path not found in player JS for netloc {}, using default fallback:"
                        + " {}",
                netloc,
                KNOWN_VIDEO_INFO_PATHS[0]);
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
                                                        cacheVideoInfoPath(domain, path);
                                                        log.info(
                                                                "Fallback path succeeded for"
                                                                        + " netloc {}: {}",
                                                                domain,
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

    private static void cacheVideoInfoPath(String netloc, String path) {
        if (netloc == null || netloc.isBlank() || path == null || !path.startsWith("/")) {
            return;
        }
        cachedVideoInfoPathByNetloc.put(netlocKey(netloc), path);
    }

    private static String netlocKey(String netloc) {
        return netloc == null ? "_unknown" : netloc.toLowerCase();
    }

    /** Visible for testing. */
    static java.util.Map<String, String> snapshotVideoInfoPathCache() {
        return java.util.Map.copyOf(cachedVideoInfoPathByNetloc);
    }

    /** Visible for testing. */
    static void clearVideoInfoPathCache() {
        cachedVideoInfoPathByNetloc.clear();
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

    /**
     * Sends the decoder POST that returns the encoded video links map.
     *
     * <p>Body MUST be {@code application/x-www-form-urlencoded} with string-typed booleans (literal
     * {@code "true"} / {@code "false"}). Sending a JSON body returns HTTP 500 with the misleading
     * error "Отсутствует или неверный токен" — see ADR 0003
     * (docs/adr/0003-kodik-decoder-post-body-format.md) and docs/quirks-and-hacks.md.
     */
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
                                        .exchangeToMono(this::handleVideoResponse))
                .map(this::parseVideoResponse)
                .doOnError(e -> healthTracker.recordFailure("step6_post_request", e.getMessage()));
    }

    /**
     * DECODE-7 (500-handling): consume the response body even on 4xx/5xx so we can classify Kodik's
     * Russian-language error message into a small enum (see {@link
     * com.orinuno.service.metrics.KodikDecoderMetrics.UpstreamErrorClass}). Successful (2xx)
     * responses are returned unchanged. Errors are logged and propagated as a typed runtime
     * exception so the surrounding retry/onErrorResume chain still gets the chance to fall back to
     * other video-info paths.
     */
    private Mono<String> handleVideoResponse(
            org.springframework.web.reactive.function.client.ClientResponse response) {
        int status = response.statusCode().value();
        if (response.statusCode().is2xxSuccessful()) {
            return response.bodyToMono(String.class);
        }
        return response.bodyToMono(String.class)
                .defaultIfEmpty("")
                .flatMap(
                        body -> {
                            com.orinuno.service.metrics.KodikDecoderMetrics.UpstreamErrorClass cls =
                                    KodikUpstreamErrorClassifier.classifyForDecoder(status, body);
                            if (decoderMetrics != null) {
                                decoderMetrics.recordUpstreamError(status, cls);
                            }
                            String preview =
                                    body == null
                                            ? "(empty)"
                                            : body.substring(0, Math.min(200, body.length()));
                            log.warn(
                                    "Kodik decoder upstream error: status={} class={} body~={}",
                                    status,
                                    cls.tag(),
                                    preview);
                            return Mono.error(
                                    new RuntimeException(
                                            "Kodik upstream error "
                                                    + status
                                                    + " ["
                                                    + cls.tag()
                                                    + "]: "
                                                    + preview));
                        });
    }

    Map<String, String> parseVideoResponse(String responseJson) {
        log.debug("[Step 7/8] Parsing video response");
        Map<String, String> videoLinks = new HashMap<>();
        boolean geoBlocked = false;

        Matcher matcher = VIDEO_SRC_PATTERN.matcher(responseJson);
        while (matcher.find()) {
            String quality = matcher.group(1);
            String encodedSrc = matcher.group(2);
            DecodeOutcome outcome = decodeVideoUrlWithProvenance(encodedSrc);
            if (decoderMetrics != null) {
                decoderMetrics.recordDecodePath(outcome.path());
                decoderMetrics.recordShiftHit(outcome.shiftUsed());
            }

            if (geoBlockDetector.isCdnGeoBlocked(outcome.url())) {
                geoBlocked = true;
            }

            videoLinks.put(quality, outcome.url());
        }

        if (geoBlocked) {
            log.warn(
                    "[Step 8/8] All {} decoded URLs are geo-blocked (kodik edge proxy detected),"
                            + " returning empty result",
                    videoLinks.size());
            if (decoderMetrics != null) {
                decoderMetrics.recordOutcome(
                        com.orinuno.service.metrics.KodikDecoderMetrics.DecodeOutcome.GEO_BLOCKED);
            }
            return new HashMap<>();
        }

        log.debug("[Step 8/8] Decoded {} video links", videoLinks.size());
        return videoLinks;
    }

    /**
     * Decodes a single Kodik video src and reports which decode path was taken.
     *
     * <p>Hardened short-circuit (DECODE-6): the previous implementation only checked for the
     * substring {@code "manifest.m3u8"} which missed several cases of "Kodik handed us a
     * pre-decoded URL" (e.g. when an upstream caching layer normalised the response). We now detect
     * any input that already starts with {@code http://}, {@code https://}, or {@code //} — those
     * are URL-shaped and must NOT be ROT-decoded. The original {@code manifest.m3u8} substring
     * check is subsumed by the URL-prefix check (a real Kodik m3u8 URL always has the scheme
     * prefix).
     *
     * <p>The returned {@link DecodeOutcome} also tells the caller which shift was used, so
     * Prometheus metrics can track ROT cipher rotations separately from short-circuit hits.
     */
    static DecodeOutcome decodeVideoUrlWithProvenance(String encoded) {
        if (encoded == null || encoded.isBlank()) {
            return new DecodeOutcome(
                    "", KodikDecoderMetrics.DecodePath.FALLBACK_NO_SHIFT_WORKED, -1);
        }
        if (looksLikePlainUrl(encoded)) {
            return new DecodeOutcome(
                    normalizeDecodedUrl(encoded),
                    KodikDecoderMetrics.DecodePath.SHORT_CIRCUIT_HTTP,
                    -1);
        }
        if (encoded.contains("manifest.m3u8")) {
            return new DecodeOutcome(
                    normalizeDecodedUrl(encoded),
                    KodikDecoderMetrics.DecodePath.SHORT_CIRCUIT_M3U8,
                    -1);
        }

        int currentShift = cachedShift.get();
        String result = tryDecodeWithShift(encoded, currentShift);
        if (result != null) {
            return new DecodeOutcome(
                    result, KodikDecoderMetrics.DecodePath.CACHED_SHIFT_HIT, currentShift);
        }

        for (int shift = 0; shift < 26; shift++) {
            if (shift == currentShift) continue;
            result = tryDecodeWithShift(encoded, shift);
            if (result != null) {
                log.info("ROT cipher shift changed: {} -> {}", currentShift, shift);
                cachedShift.set(shift);
                return new DecodeOutcome(
                        result, KodikDecoderMetrics.DecodePath.BRUTE_FORCE_NEW_SHIFT, shift);
            }
        }

        log.warn("Brute-force decode failed for all 26 shifts, using shift={}", currentShift);
        String fallback = decodeUrlSafeBase64(rotWithShift(encoded, currentShift));
        return new DecodeOutcome(
                normalizeDecodedUrl(fallback),
                KodikDecoderMetrics.DecodePath.FALLBACK_NO_SHIFT_WORKED,
                currentShift);
    }

    /** Backward-compatible wrapper for tests and callers that don't care about provenance. */
    static String decodeVideoUrl(String encoded) {
        return decodeVideoUrlWithProvenance(encoded).url();
    }

    private static boolean looksLikePlainUrl(String s) {
        return s.startsWith("http://") || s.startsWith("https://") || s.startsWith("//");
    }

    /** Outcome of a single src decode — value object used to drive metrics + DI of the URL. */
    public record DecodeOutcome(String url, KodikDecoderMetrics.DecodePath path, int shiftUsed) {}

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
