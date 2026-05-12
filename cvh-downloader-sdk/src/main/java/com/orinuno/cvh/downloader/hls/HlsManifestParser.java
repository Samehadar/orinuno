package com.orinuno.cvh.downloader.hls;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure HLS manifest classification + parsing. Stateless utility, all methods static so the segment
 * downloader and tests can call without DI.
 *
 * <p>HLS playlists come in two flavours:
 *
 * <ul>
 *   <li><b>Master playlist</b>: contains {@code #EXT-X-STREAM-INF:...} headers; each non-comment
 *       line is a <b>variant playlist</b> URI (another {@code .m3u8}, NOT a media segment).
 *   <li><b>Media playlist</b>: contains {@code #EXTINF:...} headers; each non-comment line is a
 *       media segment URI ({@code .ts}, {@code .m4s}, etc.).
 * </ul>
 */
public final class HlsManifestParser {

    private static final Pattern STREAM_INF_BANDWIDTH =
            Pattern.compile("BANDWIDTH=(\\d+)", Pattern.CASE_INSENSITIVE);

    private static final Pattern EXTINF_DURATION =
            Pattern.compile("#EXTINF:([0-9]+(?:\\.[0-9]+)?)", Pattern.CASE_INSENSITIVE);

    private HlsManifestParser() {}

    public static boolean isValidManifest(String manifestText) {
        return manifestText != null && manifestText.stripLeading().startsWith("#EXTM3U");
    }

    public static boolean isMasterPlaylist(String manifestText) {
        return manifestText != null && manifestText.contains("#EXT-X-STREAM-INF");
    }

    /**
     * Pick the highest-bandwidth variant URI in a master playlist. Returns empty when the text is
     * not a master playlist or no {@code #EXT-X-STREAM-INF} header is followed by a non-comment URI
     * line. Ties broken by declaration order (deterministic).
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
                Comparator.<Variant>comparingLong(Variant::bandwidth)
                        .reversed()
                        .thenComparingInt(Variant::order));
        return Optional.of(variants.get(0).uri());
    }

    /**
     * Extract every media segment from a media playlist, each carrying the URI and the {@code
     * #EXTINF} duration parsed from the preceding header (or {@code null} when missing/malformed).
     * Skips comments, blank lines, and any line that looks like a nested {@code .m3u8} variant
     * (defensive — if a master playlist ever sneaks past {@link #isMasterPlaylist}, we still
     * produce zero segments rather than queueing the variant URI as a "segment").
     */
    public static List<HlsSegment> extractMediaSegments(String manifestText) {
        if (manifestText == null) {
            return List.of();
        }
        List<HlsSegment> out = new ArrayList<>();
        Double pendingDuration = null;
        for (String raw : manifestText.split("\\R")) {
            String line = raw.trim();
            if (line.isEmpty()) {
                continue;
            }
            if (line.startsWith("#EXTINF")) {
                pendingDuration = parseExtInfDuration(line);
                continue;
            }
            if (line.startsWith("#")) {
                continue;
            }
            if (looksLikeVariantPlaylist(line)) {
                pendingDuration = null;
                continue;
            }
            out.add(new HlsSegment(line, pendingDuration));
            pendingDuration = null;
        }
        return out;
    }

    /** Backward-compatible URI-only view over {@link #extractMediaSegments(String)}. */
    public static List<String> extractMediaSegmentUris(String manifestText) {
        return extractMediaSegments(manifestText).stream().map(HlsSegment::url).toList();
    }

    private static boolean looksLikeVariantPlaylist(String uri) {
        int q = uri.indexOf('?');
        String pathOnly = q >= 0 ? uri.substring(0, q) : uri;
        return pathOnly.toLowerCase().endsWith(".m3u8");
    }

    private static Double parseExtInfDuration(String extInfLine) {
        Matcher m = EXTINF_DURATION.matcher(extInfLine);
        if (m.find()) {
            try {
                return Double.parseDouble(m.group(1));
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
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
