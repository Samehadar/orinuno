package com.orinuno.service.download.hls;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class HlsManifestParserTest {

    @Test
    @DisplayName("isValidManifest accepts #EXTM3U with leading whitespace")
    void validManifestDetection() {
        assertThat(HlsManifestParser.isValidManifest("#EXTM3U\n")).isTrue();
        assertThat(HlsManifestParser.isValidManifest("\n  #EXTM3U\n")).isTrue();
        assertThat(HlsManifestParser.isValidManifest(null)).isFalse();
        assertThat(HlsManifestParser.isValidManifest("")).isFalse();
        assertThat(HlsManifestParser.isValidManifest("not a playlist")).isFalse();
    }

    @Test
    @DisplayName("isMasterPlaylist detects EXT-X-STREAM-INF marker")
    void masterPlaylistDetection() {
        String master =
                "#EXTM3U\n"
                        + "#EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360\n"
                        + "720/playlist.m3u8\n";
        String media = "#EXTM3U\n#EXT-X-VERSION:3\n#EXTINF:6.0,\nseg1.ts\n#EXTINF:6.0,\nseg2.ts\n";
        assertThat(HlsManifestParser.isMasterPlaylist(master)).isTrue();
        assertThat(HlsManifestParser.isMasterPlaylist(media)).isFalse();
    }

    @Test
    @DisplayName("selectBestVariantUri picks highest bandwidth")
    void picksHighestBandwidth() {
        String master =
                "#EXTM3U\n"
                        + "#EXT-X-STREAM-INF:BANDWIDTH=400000,RESOLUTION=640x360\n"
                        + "low/playlist.m3u8\n"
                        + "#EXT-X-STREAM-INF:BANDWIDTH=2400000,RESOLUTION=1280x720\n"
                        + "high/playlist.m3u8\n"
                        + "#EXT-X-STREAM-INF:BANDWIDTH=900000,RESOLUTION=854x480\n"
                        + "med/playlist.m3u8\n";
        assertThat(HlsManifestParser.selectBestVariantUri(master)).contains("high/playlist.m3u8");
    }

    @Test
    @DisplayName("selectBestVariantUri returns empty for media playlists")
    void noVariantOnMedia() {
        String media = "#EXTM3U\n#EXT-X-VERSION:3\n#EXTINF:6.0,\nseg1.ts\n";
        assertThat(HlsManifestParser.selectBestVariantUri(media)).isEmpty();
    }

    @Test
    @DisplayName("selectBestVariantUri picks first when bandwidths tie")
    void picksFirstOnTie() {
        String master =
                "#EXTM3U\n"
                        + "#EXT-X-STREAM-INF:BANDWIDTH=2400000\n"
                        + "first/playlist.m3u8\n"
                        + "#EXT-X-STREAM-INF:BANDWIDTH=2400000\n"
                        + "second/playlist.m3u8\n";
        assertThat(HlsManifestParser.selectBestVariantUri(master)).contains("first/playlist.m3u8");
    }

    @Test
    @DisplayName(
            "selectBestVariantUri tolerates STREAM-INF without BANDWIDTH (defensive — treat as 0)")
    void tolerateMissingBandwidth() {
        String master =
                "#EXTM3U\n"
                        + "#EXT-X-STREAM-INF:RESOLUTION=640x360\n"
                        + "noband/playlist.m3u8\n"
                        + "#EXT-X-STREAM-INF:BANDWIDTH=2400000\n"
                        + "good/playlist.m3u8\n";
        assertThat(HlsManifestParser.selectBestVariantUri(master)).contains("good/playlist.m3u8");
    }

    @Test
    @DisplayName("extractMediaSegmentUris ignores comments + nested .m3u8 (defensive)")
    void extractMediaSegments() {
        String mixed =
                "#EXTM3U\n#EXT-X-VERSION:3\n"
                        + "#EXTINF:6.0,\nseg1.ts\n"
                        + "#EXTINF:6.0,\nseg2.ts\n"
                        + "should-not-appear/playlist.m3u8\n"
                        + "should-appear/seg3.ts?token=abc\n"
                        + "#EXT-X-ENDLIST\n";
        assertThat(HlsManifestParser.extractMediaSegmentUris(mixed))
                .containsExactly("seg1.ts", "seg2.ts", "should-appear/seg3.ts?token=abc");
    }

    @Test
    @DisplayName("extractMediaSegmentUris handles \\r\\n line endings")
    void crlfLineEndings() {
        String text = "#EXTM3U\r\n#EXTINF:6.0,\r\na.ts\r\n#EXTINF:6.0,\r\nb.ts\r\n";
        assertThat(HlsManifestParser.extractMediaSegmentUris(text)).containsExactly("a.ts", "b.ts");
    }

    @Test
    @DisplayName("extractMediaSegmentUris on null returns empty list")
    void nullSafe() {
        assertThat(HlsManifestParser.extractMediaSegmentUris(null)).isEmpty();
    }
}
