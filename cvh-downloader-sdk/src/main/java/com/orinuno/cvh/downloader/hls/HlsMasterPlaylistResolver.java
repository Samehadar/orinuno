package com.orinuno.cvh.downloader.hls;

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
 * Recursively resolves an HLS master playlist down to a media playlist whose lines are
 * media-segment URIs. Uses {@link HttpClient} so callers can reuse the same client instance
 * (cookies, redirects, etc.) that they use for segment GETs.
 *
 * <p>If the input manifest is already a media playlist (no {@code #EXT-X-STREAM-INF} headers), we
 * absolutize the segment URIs against the original manifest URL and return immediately.
 *
 * <p>The resolver caps recursion at {@link ResolverConfig#maxDepth()} hops to defend against
 * malicious or misconfigured CDNs that loop master playlists.
 */
@Slf4j
public class HlsMasterPlaylistResolver {

    private final HlsManifestFetcher fetcher;
    private final ResolverConfig config;

    public HlsMasterPlaylistResolver(HttpClient httpClient, ResolverConfig config) {
        this(httpClient, config, defaultFetcher(httpClient, config));
    }

    HlsMasterPlaylistResolver(
            HttpClient httpClient, ResolverConfig config, HlsManifestFetcher fetcher) {
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
                    "Master playlist resolved to variant {} (depth={})",
                    variantAbsoluteUrl,
                    depth + 1);
            byte[] variantBytes = fetcher.fetch(variantAbsoluteUrl);
            return resolveRecursive(
                    variantAbsoluteUrl,
                    new String(variantBytes, StandardCharsets.UTF_8),
                    depth + 1);
        }
        List<HlsSegment> segments = HlsManifestParser.extractMediaSegments(manifestText);
        List<HlsSegment> absolutized = new ArrayList<>(segments.size());
        for (HlsSegment s : segments) {
            absolutized.add(new HlsSegment(absolutize(manifestUrl, s.url()), s.durationSeconds()));
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

    private static HlsManifestFetcher defaultFetcher(HttpClient httpClient, ResolverConfig config) {
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

    /** Resolver tuning knobs. */
    public record ResolverConfig(int maxDepth, long fetchTimeoutMs) {
        public static ResolverConfig defaults() {
            return new ResolverConfig(3, 15_000L);
        }
    }
}
