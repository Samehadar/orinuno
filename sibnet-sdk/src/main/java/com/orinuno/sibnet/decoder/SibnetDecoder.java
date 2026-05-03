package com.orinuno.sibnet.decoder;

import com.orinuno.sibnet.SibnetConfig;
import com.orinuno.sibnet.SibnetDecodeResult;
import com.orinuno.sibnet.SibnetErrorCodes;
import com.orinuno.sibnet.parser.SibnetSourceParser;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * Sibnet decoder. Fetches the {@code shell.php} iframe HTML, extracts the {@code
 * player.src([{src:"…"}])} call via regex, resolves the captured fragment to an absolute URL.
 *
 * <p>Sibnet's anti-hotlink check requires {@code Referer: https://video.sibnet.ru/} on the page
 * fetch — the {@link SibnetConfig#referer()} header is sent on every request. If you stream the
 * resolved {@code .mp4} URL through your own proxy, the proxy must inject the same Referer upstream
 * or Sibnet will return 403.
 *
 * <p>Sibnet URLs do not expire; the resolved {@code .mp4} URL is stable across requests.
 */
@Slf4j
public final class SibnetDecoder {

    static final Pattern PLAYER_SRC =
            Pattern.compile("player\\.src\\(\\s*\\[\\s*\\{\\s*src:\\s*\"([^\"]+)\"");

    private final SibnetConfig config;
    private final WebClient client;

    public SibnetDecoder(SibnetConfig config, WebClient.Builder webClientBuilder) {
        this.config = config;
        this.client =
                webClientBuilder
                        .defaultHeader("Referer", config.referer())
                        .defaultHeader("User-Agent", config.userAgent())
                        .build();
    }

    public Mono<SibnetDecodeResult> decode(long videoId) {
        return decode(SibnetSourceParser.toIframeUrl(videoId));
    }

    public Mono<SibnetDecodeResult> decode(String shellUrl) {
        return client.get()
                .uri(shellUrl)
                .retrieve()
                .onStatus(HttpStatusCode::is4xxClientError, this::handle4xx)
                .bodyToMono(String.class)
                .map(html -> extractFromHtml(html, shellUrl, config.baseUrl()))
                .onErrorResume(
                        ex -> {
                            if (ex instanceof SibnetVideoNotFoundException) {
                                return Mono.just(
                                        SibnetDecodeResult.failure(
                                                SibnetErrorCodes.SIBNET_VIDEO_NOT_FOUND));
                            }
                            log.warn("Sibnet decode error for {}: {}", shellUrl, ex.toString());
                            return Mono.just(
                                    SibnetDecodeResult.failure(
                                            SibnetErrorCodes.SIBNET_FETCH_ERROR));
                        });
    }

    private Mono<? extends Throwable> handle4xx(
            org.springframework.web.reactive.function.client.ClientResponse response) {
        int status = response.statusCode().value();
        if (status == 404) {
            return Mono.error(new SibnetVideoNotFoundException());
        }
        return response.createException().map(ex -> (Throwable) ex);
    }

    public static SibnetDecodeResult extractFromHtml(String html, String shellUrl, String baseUrl) {
        if (html == null || html.isBlank()) {
            return SibnetDecodeResult.failure(SibnetErrorCodes.SIBNET_PLAYER_REGEX_BREAK);
        }
        Matcher matcher = PLAYER_SRC.matcher(html);
        if (!matcher.find()) {
            return SibnetDecodeResult.failure(SibnetErrorCodes.SIBNET_PLAYER_REGEX_BREAK);
        }
        String src = matcher.group(1).trim();
        Optional<String> absolute = absolutize(src, shellUrl, baseUrl);
        if (absolute.isEmpty()) {
            return SibnetDecodeResult.failure(SibnetErrorCodes.SIBNET_INVALID_SRC);
        }
        return SibnetDecodeResult.success(Map.of("720", absolute.get()), "video/mp4");
    }

    public static Optional<String> absolutize(String src, String shellUrl, String baseUrl) {
        if (src == null || src.isBlank()) {
            return Optional.empty();
        }
        if (src.startsWith("http://") || src.startsWith("https://")) {
            return Optional.of(src);
        }
        if (src.startsWith("//")) {
            return Optional.of("https:" + src);
        }
        // baseUrl may be passed with or without a trailing slash; trim defensively before joining.
        String stripped =
                baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        if (src.startsWith("/")) {
            return Optional.of(stripped + src);
        }
        return Optional.of(stripped + "/" + src);
    }

    /**
     * 404 from Sibnet means the video is gone (deleted, removed). Permanent — do not retry.
     * Surfaced as {@link SibnetErrorCodes#SIBNET_VIDEO_NOT_FOUND} on the result.
     */
    public static final class SibnetVideoNotFoundException extends RuntimeException {
        public SibnetVideoNotFoundException() {
            super("Sibnet video not found (HTTP 404)");
        }
    }
}
