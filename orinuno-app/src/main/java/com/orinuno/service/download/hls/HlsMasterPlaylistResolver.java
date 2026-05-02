package com.orinuno.service.download.hls;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;

/**
 * DOWNLOAD-PARALLEL — recursively resolves an HLS master playlist down to a media playlist whose
 * lines are media-segment URIs. Plays nicely with {@code java.net.http.HttpClient} so callers can
 * reuse the same client instance (cookies, redirects, etc.) that they use for segment GETs.
 *
 * <p>If the input manifest is already a media playlist (no {@code #EXT-X-STREAM-INF} headers), we
 * absolutize the segment URIs against the original manifest URL and return immediately — no extra
 * network calls, no surprises.
 *
 * <p>The resolver caps recursion at {@link OrinunoHlsResolverConfig#maxDepth()} hops to defend
 * against malicious / misconfigured CDNs that loop master playlists. Beyond the cap, we throw an
 * {@link IOException} so the caller can fail fast rather than silently produce a broken download.
 */
@Slf4j
public class HlsMasterPlaylistResolver {

    private final HttpClient httpClient;
    private final HlsManifestFetcher fetcher;
    private final OrinunoHlsResolverConfig config;

    public HlsMasterPlaylistResolver(HttpClient httpClient, OrinunoHlsResolverConfig config) {
        this(httpClient, config, defaultFetcher(httpClient, config));
    }

    HlsMasterPlaylistResolver(
            HttpClient httpClient, OrinunoHlsResolverConfig config, HlsManifestFetcher fetcher) {
        this.httpClient = httpClient;
        this.config = config;
        this.fetcher = fetcher;
    }

    public HlsMediaPlaylist resolve(String manifestUrl, byte[] manifestBytes) throws IOException {
        return resolveRecursive(manifestUrl, new String(manifestBytes, StandardCharsets.UTF_8), 0);
    }

    private HlsMediaPlaylist resolveRecursive(String manifestUrl, String manifestText, int depth)
            throws IOException {
        if (!HlsManifestParser.isValidManifest(manifestText)) {
            throw new IOException(
                    "HLS manifest does not start with #EXTM3U (url=" + manifestUrl + ")");
        }
        if (HlsManifestParser.isMasterPlaylist(manifestText)) {
            if (depth >= config.maxDepth()) {
                throw new IOException(
                        "HLS master-playlist recursion exceeded depth="
                                + config.maxDepth()
                                + " (url="
                                + manifestUrl
                                + ")");
            }
            String variantUri =
                    HlsManifestParser.selectBestVariantUri(manifestText)
                            .orElseThrow(
                                    () ->
                                            new IOException(
                                                    "HLS master playlist has no resolvable"
                                                            + " variants (url="
                                                            + manifestUrl
                                                            + ")"));
            String variantAbsoluteUrl = absolutize(manifestUrl, variantUri);
            log.debug(
                    "DOWNLOAD-PARALLEL: master playlist resolved to variant {} (depth={})",
                    variantAbsoluteUrl,
                    depth + 1);
            byte[] variantBytes = fetcher.fetch(variantAbsoluteUrl);
            return resolveRecursive(
                    variantAbsoluteUrl,
                    new String(variantBytes, StandardCharsets.UTF_8),
                    depth + 1);
        }
        List<String> segmentUris = HlsManifestParser.extractMediaSegmentUris(manifestText);
        List<String> absolutized = new ArrayList<>(segmentUris.size());
        for (String s : segmentUris) {
            absolutized.add(absolutize(manifestUrl, s));
        }
        return new HlsMediaPlaylist(manifestUrl, absolutized);
    }

    static String absolutize(String baseManifestUrl, String segmentOrVariantUri) {
        String trimmed = segmentOrVariantUri.trim();
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            return trimmed;
        }
        if (trimmed.startsWith("//")) {
            int schemeEnd = baseManifestUrl.indexOf("://");
            String scheme = schemeEnd > 0 ? baseManifestUrl.substring(0, schemeEnd) : "https";
            return scheme + ":" + trimmed;
        }
        int slash = baseManifestUrl.lastIndexOf('/');
        String base = slash >= 0 ? baseManifestUrl.substring(0, slash + 1) : baseManifestUrl + "/";
        if (trimmed.startsWith("./")) {
            return base + trimmed.substring(2);
        }
        if (trimmed.startsWith("/")) {
            try {
                URI baseUri = URI.create(baseManifestUrl);
                String origin = baseUri.getScheme() + "://" + baseUri.getRawAuthority();
                return origin + trimmed;
            } catch (Exception e) {
                return base + trimmed;
            }
        }
        return base + trimmed;
    }

    /** Fetches a manifest from a URL. Pulled out for testability. */
    @FunctionalInterface
    public interface HlsManifestFetcher {
        byte[] fetch(String url) throws IOException;
    }

    private static HlsManifestFetcher defaultFetcher(
            HttpClient httpClient, OrinunoHlsResolverConfig config) {
        return url -> {
            HttpRequest request =
                    HttpRequest.newBuilder()
                            .uri(URI.create(url))
                            .timeout(Duration.ofMillis(config.fetchTimeoutMs()))
                            .GET()
                            .build();
            try {
                HttpResponse<byte[]> response =
                        httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    throw new IOException(
                            "HLS variant fetch returned HTTP "
                                    + response.statusCode()
                                    + " for "
                                    + url);
                }
                byte[] body = response.body();
                return body != null ? body : new byte[0];
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while fetching HLS variant " + url, e);
            }
        };
    }

    /** Bag of resolver knobs separate from {@code OrinunoProperties} to keep wiring testable. */
    public record OrinunoHlsResolverConfig(int maxDepth, long fetchTimeoutMs) {
        public static OrinunoHlsResolverConfig defaults() {
            return new OrinunoHlsResolverConfig(3, 15_000L);
        }
    }
}
