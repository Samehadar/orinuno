package com.orinuno.cvh.decoder;

import com.orinuno.cvh.CvhConfig;
import com.orinuno.cvh.CvhDecodeResult;
import com.orinuno.cvh.CvhErrorCodes;
import com.orinuno.cvh.api.CvhApiClient;
import com.orinuno.cvh.api.CvhApiException;
import com.orinuno.cvh.api.CvhVideoSourcesCache;
import com.orinuno.cvh.host.CvhHostPageParser;
import com.orinuno.cvh.host.CvhHostRegistry;
import com.orinuno.cvh.model.AnimeContent;
import com.orinuno.cvh.model.AnimeWithSources;
import com.orinuno.cvh.model.CvhVideoSources;
import com.orinuno.cvh.model.CvhVoiceTrack;
import com.orinuno.cvh.model.TrackWithSources;
import jakarta.annotation.Nullable;
import java.net.URI;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;

/**
 * Reactive pipeline that turns a host-page URL into {@link AnimeWithSources}.
 *
 * <pre>
 *   pageUrl
 *     ├── hostRegistry.resolve → CvhHostPageParser (or CVH_UNSUPPORTED_HOST)
 *     ├── WebClient GET → HTML
 *     ├── host.parse → AnimeContent
 *     ├── if cvhTitleId==null → AnimeWithSources(content, [])    // CVH not embedded
 *     ├── CvhApiClient.getTitleVoiceTracks → List&lt;CvhVoiceTrack&gt;
 *     └── per track → cache.getOrFetch(vkId) → TrackWithSources
 * </pre>
 *
 * <p>Referer for CVH plapi calls is derived from the host page origin ({@code scheme://host/}) —
 * CVH gates plapi access by publisher-whitelisted referers, so it must match the host page (e.g.
 * {@code https://jut-su.works/}), not the player iframe origin.
 */
@Slf4j
public final class CvhPipelineDecoder {

    private final CvhHostRegistry hostRegistry;
    private final CvhApiClient apiClient;
    private final CvhVideoSourcesCache cache;
    private final WebClient pageFetcher;

    public CvhPipelineDecoder(
            CvhConfig config,
            CvhHostRegistry hostRegistry,
            CvhApiClient apiClient,
            CvhVideoSourcesCache cache,
            WebClient.Builder webClientBuilder) {
        this.hostRegistry = hostRegistry;
        this.apiClient = apiClient;
        this.cache = cache;
        // jut-su.works/random and similar discovery endpoints respond 302 — follow redirects so the
        // pipeline lands on the actual title page HTML rather than an empty redirect body. The
        // BiPredicate variant guards against SSRF: only same-host redirects (relative or to another
        // registered host) are followed; any 3xx pointing off-registry is dropped.
        this.pageFetcher =
                webClientBuilder
                        .clientConnector(
                                new ReactorClientHttpConnector(
                                        HttpClient.create()
                                                .followRedirect(
                                                        (req, res) ->
                                                                isRedirectAllowed(
                                                                        res.responseHeaders()
                                                                                .get("Location"),
                                                                        hostRegistry))))
                        .defaultHeader("User-Agent", config.userAgent())
                        .defaultHeader("Accept-Language", "ru-RU,ru;q=0.9,en;q=0.8")
                        .defaultHeader(
                                "Accept",
                                "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                        .build();
    }

    /**
     * Decides whether to follow a 3xx redirect. Allowed targets:
     *
     * <ul>
     *   <li>Relative ({@code /some-slug}) — inherits original host; safe.
     *   <li>Absolute http(s) URL whose host is recognised by some registered {@link
     *       com.orinuno.cvh.host.CvhHostPageParser}.
     * </ul>
     *
     * <p>Everything else (file://, gopher://, private IPs, off-registry hosts) is rejected to block
     * SSRF — a malicious page can otherwise 302 the pipeline at internal infrastructure (link-local
     * 169.254.169.254, 127.0.0.1, RFC1918 networks).
     */
    static boolean isRedirectAllowed(@Nullable String location, CvhHostRegistry hostRegistry) {
        if (location == null || location.isBlank()) {
            return false;
        }
        try {
            URI target = URI.create(location);
            if (!target.isAbsolute()) {
                return true;
            }
            String scheme = target.getScheme();
            if (scheme == null
                    || (!"https".equalsIgnoreCase(scheme) && !"http".equalsIgnoreCase(scheme))) {
                return false;
            }
            return hostRegistry.resolve(target).isPresent();
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    public Mono<CvhDecodeResult> decode(String pageUrl) {
        String referer = deriveReferer(pageUrl);
        return hostRegistry
                .resolve(pageUrl)
                .map(host -> fetchAndParse(host, pageUrl).flatMap(c -> resolveTracks(c, referer)))
                .orElseGet(
                        () ->
                                Mono.just(
                                        CvhDecodeResult.failure(
                                                CvhErrorCodes.CVH_UNSUPPORTED_HOST)))
                .onErrorResume(
                        ex -> {
                            String code = mapErrorCode(ex);
                            log.warn(
                                    "CVH decode failed pageUrl={}: {} [{}]",
                                    sanitizeForLog(pageUrl),
                                    ex.toString(),
                                    code);
                            return Mono.just(CvhDecodeResult.failure(code));
                        });
    }

    public Mono<CvhVideoSources> getSourcesByVkId(String vkId) {
        return cache.getOrFetch(vkId);
    }

    public Mono<CvhVideoSources> getSourcesByVkId(String vkId, @Nullable String referer) {
        return cache.getOrFetch(vkId, referer);
    }

    private Mono<AnimeContent> fetchAndParse(CvhHostPageParser host, String pageUrl) {
        return pageFetcher
                .get()
                .uri(pageUrl)
                .retrieve()
                .bodyToMono(String.class)
                .onErrorMap(
                        ex ->
                                ex instanceof CvhApiException
                                        ? ex
                                        : new CvhApiException(CvhErrorCodes.CVH_FETCH_ERROR, ex))
                .map(html -> host.parse(html, pageUrl));
    }

    private Mono<CvhDecodeResult> resolveTracks(AnimeContent content, String referer) {
        if (content.cvhTitleId() == null || content.cvhTitleId().isBlank()) {
            return Mono.just(CvhDecodeResult.success(new AnimeWithSources(content, List.of())));
        }
        return apiClient
                .getTitleVoiceTracks(
                        content.cvhTitleId(),
                        content.cvhPublisherId(),
                        content.cvhAggregator(),
                        referer)
                .flatMap(tracks -> attachSources(content, tracks, referer));
    }

    private Mono<CvhDecodeResult> attachSources(
            AnimeContent content, List<CvhVoiceTrack> tracks, String referer) {
        if (tracks.isEmpty()) {
            return Mono.just(CvhDecodeResult.success(new AnimeWithSources(content, List.of())));
        }
        return Flux.fromIterable(tracks)
                .filter(t -> t.vkId() != null && !t.vkId().isBlank())
                .concatMap(
                        track ->
                                cache.getOrFetch(track.vkId(), referer)
                                        .map(s -> new TrackWithSources(track, s))
                                        .onErrorResume(
                                                ex -> {
                                                    log.warn(
                                                            "CVH track resolve failed vkId={}: {}",
                                                            track.vkId(),
                                                            ex.toString());
                                                    return Mono.empty();
                                                }))
                .collectList()
                .map(list -> CvhDecodeResult.success(new AnimeWithSources(content, list)));
    }

    /**
     * Builds a CORS-compatible Referer from a host page URL: {@code scheme://host/}. Returns the
     * empty string on parse failure so the API client falls back to its config default.
     *
     * <p>Hosts containing control characters (CR/LF/TAB) are rejected — they would otherwise enable
     * header-injection in the downstream {@code Referer} header.
     */
    static String deriveReferer(String pageUrl) {
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
            if (containsControlChar(host) || containsControlChar(scheme)) {
                return "";
            }
            return scheme + "://" + host + "/";
        } catch (Exception ex) {
            return "";
        }
    }

    private static boolean containsControlChar(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < 0x20 || c == 0x7F) {
                return true;
            }
        }
        return false;
    }

    /**
     * Replaces CR/LF/TAB in a string before it lands in a log message — prevents log-injection by
     * callers passing crafted URLs.
     */
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
        if (ex instanceof CvhApiException api) {
            return api.errorCode();
        }
        return CvhErrorCodes.CVH_FETCH_ERROR;
    }
}
