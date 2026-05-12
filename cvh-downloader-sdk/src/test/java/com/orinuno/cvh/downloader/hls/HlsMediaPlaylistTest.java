package com.orinuno.cvh.downloader.hls;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class HlsMediaPlaylistTest {

    @Test
    void segmentUrlsDerivedFromSegments() {
        HlsMediaPlaylist p =
                new HlsMediaPlaylist(
                        "https://cdn/x.m3u8",
                        List.of(
                                new HlsSegment("https://cdn/seg1.ts", 5.0),
                                new HlsSegment("https://cdn/seg2.ts", 5.0)));
        assertThat(p.segmentUrls()).containsExactly("https://cdn/seg1.ts", "https://cdn/seg2.ts");
        assertThat(p.size()).isEqualTo(2);
        assertThat(p.isEmpty()).isFalse();
    }

    @Test
    void totalDurationSecondsSumsExtInf() {
        HlsMediaPlaylist p =
                new HlsMediaPlaylist(
                        "https://cdn/x.m3u8",
                        List.of(
                                new HlsSegment("a.ts", 4.5),
                                new HlsSegment("b.ts", 5.5),
                                new HlsSegment("c.ts", 2.0)));
        assertThat(p.totalDurationSeconds()).isPresent();
        assertThat(p.totalDurationSeconds().getAsDouble()).isEqualTo(12.0);
    }

    @Test
    void totalDurationSecondsIgnoresNullEntries() {
        HlsMediaPlaylist p =
                new HlsMediaPlaylist(
                        "https://cdn/x.m3u8",
                        List.of(
                                new HlsSegment("a.ts", 4.5),
                                new HlsSegment("b.ts", null),
                                new HlsSegment("c.ts", 5.5)));
        assertThat(p.totalDurationSeconds().getAsDouble()).isEqualTo(10.0);
    }

    @Test
    void totalDurationSecondsEmptyWhenAllNull() {
        HlsMediaPlaylist p =
                new HlsMediaPlaylist(
                        "https://cdn/x.m3u8",
                        List.of(new HlsSegment("a.ts", null), new HlsSegment("b.ts", null)));
        assertThat(p.totalDurationSeconds()).isEmpty();
    }

    @Test
    void emptySegmentsAccepted() {
        HlsMediaPlaylist p = new HlsMediaPlaylist("https://cdn/x.m3u8", List.of());
        assertThat(p.isEmpty()).isTrue();
        assertThat(p.size()).isZero();
        assertThat(p.segmentUrls()).isEmpty();
        assertThat(p.totalDurationSeconds()).isEmpty();
    }

    @Test
    void nullSegmentsListBecomesEmpty() {
        HlsMediaPlaylist p = new HlsMediaPlaylist("https://cdn/x.m3u8", null);
        assertThat(p.segments()).isEmpty();
    }
}
