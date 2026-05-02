package com.orinuno.service.provider.sibnet;

import com.orinuno.client.http.RotatingUserAgentProvider;
import com.orinuno.service.provider.ProviderDecodeResult;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * PLAYER-3 (ADR 0006) — Sibnet decoder. Fetches the {@code shell.php} iframe HTML, extracts the
 * playlist URL via regex, resolves it to an absolute URL.
 *
 * <p>Sibnet's anti-hotlink check requires {@code Referer: https://video.sibnet.ru/} on the page
 * fetch. The streaming proxy must inject the same Referer when serving the resolved URL — see
 * docs/quirks-and-hacks.md for details.
 *
 * <p>Sibnet URLs do not expire; the resolved {@code .mp4} URL is stable across requests.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SibnetDecoderService {

    static final Pattern PLAYER_SRC =
            Pattern.compile("player\\.src\\(\\s*\\[\\s*\\{\\s*src:\\s*\"([^\"]+)\"");

    static final String REFERER = "https://video.sibnet.ru/";

    private final WebClient.Builder webClientBuilder;
    private final RotatingUserAgentProvider userAgents;

    public Mono<ProviderDecodeResult> decode(long videoId) {
        return decode(SibnetSourceParser.toIframeUrl(videoId));
    }

    public Mono<ProviderDecodeResult> decode(String shellUrl) {
        WebClient client =
                webClientBuilder
                        .defaultHeader("Referer", REFERER)
                        .defaultHeader("User-Agent", userAgents.stableDesktop())
                        .build();
        return client.get()
                .uri(shellUrl)
                .retrieve()
                .onStatus(HttpStatusCode::is4xxClientError, this::handle4xx)
                .bodyToMono(String.class)
                .map(html -> extractFromHtml(html, shellUrl))
                .onErrorResume(
                        ex -> {
                            log.warn(
                                    "PLAYER-3 Sibnet decode error for {}: {}",
                                    shellUrl,
                                    ex.toString());
                            return Mono.just(ProviderDecodeResult.failure("SIBNET_FETCH_ERROR"));
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

    static ProviderDecodeResult extractFromHtml(String html, String shellUrl) {
        if (html == null || html.isBlank()) {
            return ProviderDecodeResult.failure("SIBNET_PLAYER_REGEX_BREAK");
        }
        Matcher matcher = PLAYER_SRC.matcher(html);
        if (!matcher.find()) {
            return ProviderDecodeResult.failure("SIBNET_PLAYER_REGEX_BREAK");
        }
        String src = matcher.group(1).trim();
        Optional<String> absolute = absolutize(src, shellUrl);
        if (absolute.isEmpty()) {
            return ProviderDecodeResult.failure("SIBNET_INVALID_SRC");
        }
        return ProviderDecodeResult.success(Map.of("720", absolute.get()), "video/mp4");
    }

    static Optional<String> absolutize(String src, String shellUrl) {
        if (src == null || src.isBlank()) {
            return Optional.empty();
        }
        if (src.startsWith("http://") || src.startsWith("https://")) {
            return Optional.of(src);
        }
        if (src.startsWith("//")) {
            return Optional.of("https:" + src);
        }
        if (src.startsWith("/")) {
            return Optional.of("https://video.sibnet.ru" + src);
        }
        return Optional.of("https://video.sibnet.ru/" + src);
    }

    /** 404 from Sibnet means the video is gone (deleted, removed). Permanent — do not retry. */
    public static final class SibnetVideoNotFoundException extends RuntimeException {
        public SibnetVideoNotFoundException() {
            super("Sibnet video not found (HTTP 404)");
        }
    }
}
