package com.orinuno.service.download.hls;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * DOWNLOAD-PARALLEL — pure HLS manifest classification + parsing. Stateless utility, all methods
 * static so the segment downloader and tests can call without DI.
 *
 * <p>HLS playlists come in two flavours:
 *
 * <ul>
 *   <li><b>Master playlist</b>: contains {@code #EXT-X-STREAM-INF:...} headers; each non-comment
 *       line is a <b>variant playlist</b> URI (another {@code .m3u8}, NOT a media segment).
 *   <li><b>Media playlist</b>: contains {@code #EXTINF:...} headers; each non-comment line is a
 *       media segment URI ({@code .ts}, {@code .m4s}, etc.).
 * </ul>
 *
 * <p>Before DOWNLOAD-PARALLEL, the {@code PlaywrightVideoFetcher.downloadHlsSegments} loop treated
 * every non-{@code #} line as a segment, which silently broke downloads if Kodik ever served a
 * master playlist (the variant {@code .m3u8} URI got fetched as a single broken {@code .ts}).
 */
public final class HlsManifestParser {

    private static final Pattern STREAM_INF_BANDWIDTH =
            Pattern.compile("BANDWIDTH=(\\d+)", Pattern.CASE_INSENSITIVE);

    private HlsManifestParser() {}

    public static boolean isValidManifest(String manifestText) {
        return manifestText != null && manifestText.stripLeading().startsWith("#EXTM3U");
    }

    public static boolean isMasterPlaylist(String manifestText) {
        return manifestText != null && manifestText.contains("#EXT-X-STREAM-INF");
    }

    /**
     * Pick the highest-bandwidth variant URI in a master playlist. Returns empty when:
     *
     * <ul>
     *   <li>The text is not a master playlist
     *   <li>No {@code #EXT-X-STREAM-INF} header is followed by a non-comment URI line
     * </ul>
     *
     * <p>If two variants advertise the same bandwidth, the first one in declaration order wins
     * (deterministic).
     */
    public static Optional<String> selectBestVariantUri(String manifestText) {
        if (!isMasterPlaylist(manifestText)) {
            return Optional.empty();
        }
        List<Variant> variants = new ArrayList<>();
        String[] lines = manifestText.split("\\R");
        long pendingBandwidth = -1;
        int order = 0;
        for (String raw : lines) {
            String line = raw.trim();
            if (line.isEmpty()) {
                continue;
            }
            if (line.startsWith("#EXT-X-STREAM-INF")) {
                pendingBandwidth = parseBandwidth(line);
                continue;
            }
            if (line.startsWith("#")) {
                continue;
            }
            if (pendingBandwidth >= 0) {
                variants.add(new Variant(line, pendingBandwidth, order++));
                pendingBandwidth = -1;
            }
        }
        if (variants.isEmpty()) {
            return Optional.empty();
        }
        variants.sort(
                Comparator.<Variant>comparingLong(v -> v.bandwidth())
                        .reversed()
                        .thenComparingInt(Variant::order));
        return Optional.of(variants.get(0).uri());
    }

    /**
     * Extract every media-segment URI from a media playlist. Skips comments, blank lines, and any
     * line that looks like a nested {@code .m3u8} variant (defensive — if a master playlist ever
     * sneaks past {@link #isMasterPlaylist}, we still produce zero segments rather than queueing
     * the variant URI as a "segment").
     */
    public static List<String> extractMediaSegmentUris(String manifestText) {
        if (manifestText == null) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String raw : manifestText.split("\\R")) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            if (looksLikeVariantPlaylist(line)) {
                continue;
            }
            out.add(line);
        }
        return out;
    }

    private static boolean looksLikeVariantPlaylist(String uri) {
        int q = uri.indexOf('?');
        String pathOnly = q >= 0 ? uri.substring(0, q) : uri;
        return pathOnly.toLowerCase().endsWith(".m3u8");
    }

    private static long parseBandwidth(String streamInfLine) {
        Matcher m = STREAM_INF_BANDWIDTH.matcher(streamInfLine);
        if (m.find()) {
            try {
                return Long.parseLong(m.group(1));
            } catch (NumberFormatException ignored) {
                return 0L;
            }
        }
        return 0L;
    }

    record Variant(String uri, long bandwidth, int order) {}
}
