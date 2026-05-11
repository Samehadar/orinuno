package com.orinuno.aksor.decoder;

import com.orinuno.aksor.AksorConfig;
import com.orinuno.aksor.AksorDecodeResult;
import com.orinuno.aksor.AksorErrorCodes;
import com.orinuno.aksor.AksorException;
import com.orinuno.aksor.api.AksorApiClient;
import com.orinuno.aksor.host.AksorHostRegistry;
import com.orinuno.aksor.model.AksorAnime;
import jakarta.annotation.Nullable;
import java.net.URI;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Reactive pipeline that turns a host-page URL into a fully populated {@link AksorAnime}.
 *
 * <pre>
 *   pageUrl
 *     ├── hostRegistry.resolve → AksorHostPageParser
 *     ├── host.resolve → AksorAnime with episode list (each carries a hash, qualities=null)
 *     ├── for each episode: AksorApiClient.getQualities(hash, referer) → AksorVideoQualities
 *     └── enrich each episode and return AksorAnime
 * </pre>
 */
@Slf4j
public final class AksorPipelineDecoder {

    private final AksorConfig config;
    private final AksorHostRegistry hostRegistry;
    private final AksorApiClient apiClient;

    public AksorPipelineDecoder(
            AksorConfig config, AksorHostRegistry hostRegistry, AksorApiClient apiClient) {
        this.config = config;
        this.hostRegistry = hostRegistry;
        this.apiClient = apiClient;
    }

    public Mono<AksorDecodeResult> decode(String pageUrl) {
        return hostRegistry
                .resolve(pageUrl)
                .map(host -> host.resolve(pageUrl).flatMap(anime -> enrich(anime, pageUrl)))
                .orElseGet(
                        () ->
                                Mono.just(
                                        AksorDecodeResult.failure(
                                                AksorErrorCodes.AKSOR_UNSUPPORTED_HOST)))
                .onErrorResume(
                        ex -> {
                            String code = mapErrorCode(ex);
                            log.warn(
                                    "Aksor decode failed pageUrl={}: {} [{}]",
                                    sanitizeForLog(pageUrl),
                                    ex.toString(),
                                    code);
                            return Mono.just(AksorDecodeResult.failure(code));
                        });
    }

    private Mono<AksorDecodeResult> enrich(AksorAnime anime, String pageUrl) {
        if (anime.episodes().isEmpty()) {
            return Mono.just(AksorDecodeResult.failure(AksorErrorCodes.AKSOR_NO_EPISODES));
        }
        String referer = deriveReferer(pageUrl);
        return Flux.fromIterable(anime.episodes())
                .flatMapSequential(
                        ep ->
                                apiClient
                                        .getQualities(ep.hash(), referer)
                                        .map(ep::withQualities)
                                        .onErrorResume(
                                                ex -> {
                                                    log.warn(
                                                            "Aksor qualities fetch failed hash={}:"
                                                                    + " {}",
                                                            ep.hash(),
                                                            ex.toString());
                                                    return Mono.just(ep);
                                                }),
                        Math.max(1, config.episodeFetchConcurrency()))
                .collectList()
                .map(
                        list ->
                                AksorDecodeResult.success(
                                        new com.orinuno.aksor.model.AksorAnime(
                                                anime.animeId(),
                                                anime.slug(),
                                                anime.pageUrl(),
                                                anime.title(),
                                                anime.posterUrl(),
                                                list)));
    }

    static String deriveReferer(@Nullable String pageUrl) {
        if (pageUrl == null || pageUrl.isBlank()) {
            return "";
        }
        try {
            URI uri = new URI(pageUrl);
            String scheme = uri.getScheme();
            String host = uri.getHost();
            if (scheme == null || host == null) {
                return "";
            }
            if (containsControlChar(scheme) || containsControlChar(host)) {
                return "";
            }
            return scheme + "://" + host + "/";
        } catch (Exception ex) {
            return "";
        }
    }

    private static boolean containsControlChar(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 0x20 || c == 0x7F) {
                return true;
            }
        }
        return false;
    }

    static String sanitizeForLog(@Nullable String value) {
        if (value == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            sb.append(c < 0x20 || c == 0x7F ? '_' : c);
        }
        return sb.toString();
    }

    private static String mapErrorCode(Throwable ex) {
        if (ex instanceof AksorException ae) {
            return ae.errorCode();
        }
        return AksorErrorCodes.AKSOR_FETCH_ERROR;
    }
}
