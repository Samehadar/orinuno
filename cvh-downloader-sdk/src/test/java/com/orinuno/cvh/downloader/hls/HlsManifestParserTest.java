package com.orinuno.cvh.downloader.hls;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class HlsManifestParserTest {

    @Test
    void detectsMasterPlaylist() {
        String text =
                "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=2000000\nvariant720.m3u8\n"
                        + "#EXT-X-STREAM-INF:BANDWIDTH=5000000\nvariant1080.m3u8\n";
        assertThat(HlsManifestParser.isMasterPlaylist(text)).isTrue();
        assertThat(HlsManifestParser.selectBestVariantUri(text)).contains("variant1080.m3u8");
    }

    @Test
    void mediaPlaylistSegmentsExtracted() {
        String text = "#EXTM3U\n#EXTINF:5.0,\nseg1.ts\n#EXTINF:5.0,\nseg2.ts\n";
        assertThat(HlsManifestParser.isMasterPlaylist(text)).isFalse();
        assertThat(HlsManifestParser.extractMediaSegmentUris(text))
                .containsExactly("seg1.ts", "seg2.ts");
    }

    @Test
    void invalidManifestDetected() {
        assertThat(HlsManifestParser.isValidManifest("not a manifest")).isFalse();
        assertThat(HlsManifestParser.isValidManifest(null)).isFalse();
        assertThat(HlsManifestParser.isValidManifest("#EXTM3U\n")).isTrue();
    }

    @Test
    void variantUrisSkippedFromSegmentList() {
        String text = "#EXTM3U\nfake.m3u8\nseg1.ts\nfake2.m3u8?token=abc\n";
        assertThat(HlsManifestParser.extractMediaSegmentUris(text)).containsExactly("seg1.ts");
    }
}
