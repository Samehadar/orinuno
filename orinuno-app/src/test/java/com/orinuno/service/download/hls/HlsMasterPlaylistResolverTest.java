package com.orinuno.service.download.hls;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.orinuno.service.download.hls.HlsMasterPlaylistResolver.HlsManifestFetcher;
import com.orinuno.service.download.hls.HlsMasterPlaylistResolver.OrinunoHlsResolverConfig;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class HlsMasterPlaylistResolverTest {

    private static final String MASTER_NESTED =
            "#EXTM3U\n"
                    + "#EXT-X-STREAM-INF:BANDWIDTH=400000,RESOLUTION=640x360\n"
                    + "low/playlist.m3u8\n"
                    + "#EXT-X-STREAM-INF:BANDWIDTH=2400000,RESOLUTION=1280x720\n"
                    + "high/playlist.m3u8\n";

    private static final String MEDIA_PLAYLIST =
            "#EXTM3U\n#EXT-X-VERSION:3\n#EXTINF:6.0,\nseg1.ts\n#EXTINF:6.0,\nseg2.ts\n";

    @Test
    @DisplayName("resolve(media playlist) returns absolutized segment URIs without HTTP fetch")
    void mediaPlaylistResolvesInline() throws IOException {
        TrackingFetcher fetcher = new TrackingFetcher(Map.of());
        HlsMasterPlaylistResolver resolver = newResolver(fetcher);

        HlsMediaPlaylist resolved =
                resolver.resolve(
                        "https://cdn/host/show/360/playlist.m3u8",
                        MEDIA_PLAYLIST.getBytes(StandardCharsets.UTF_8));

        assertThat(resolved.segmentUrls())
                .containsExactly(
                        "https://cdn/host/show/360/seg1.ts", "https://cdn/host/show/360/seg2.ts");
        assertThat(resolved.manifestUrl()).isEqualTo("https://cdn/host/show/360/playlist.m3u8");
        assertThat(fetcher.fetchedUrls).isEmpty();
    }

    @Test
    @DisplayName("resolve(master playlist) fetches highest-bandwidth variant + absolutizes")
    void masterPlaylistResolvesViaFetch() throws IOException {
        TrackingFetcher fetcher =
                new TrackingFetcher(
                        Map.of(
                                "https://cdn/show/master/high/playlist.m3u8",
                                MEDIA_PLAYLIST.getBytes(StandardCharsets.UTF_8)));
        HlsMasterPlaylistResolver resolver = newResolver(fetcher);

        HlsMediaPlaylist resolved =
                resolver.resolve(
                        "https://cdn/show/master/master.m3u8",
                        MASTER_NESTED.getBytes(StandardCharsets.UTF_8));

        assertThat(fetcher.fetchedUrls)
                .containsExactly("https://cdn/show/master/high/playlist.m3u8");
        assertThat(resolved.manifestUrl()).isEqualTo("https://cdn/show/master/high/playlist.m3u8");
        assertThat(resolved.segmentUrls())
                .containsExactly(
                        "https://cdn/show/master/high/seg1.ts",
                        "https://cdn/show/master/high/seg2.ts");
    }

    @Test
    @DisplayName("resolve aborts when master playlist recursion exceeds maxDepth")
    void recursionDepthCapped() {
        Map<String, byte[]> store = new HashMap<>();
        store.put("https://cdn/master2.m3u8", MASTER_NESTED.getBytes(StandardCharsets.UTF_8));
        store.put("https://cdn/high/playlist.m3u8", MASTER_NESTED.getBytes(StandardCharsets.UTF_8));
        TrackingFetcher fetcher = new TrackingFetcher(store);
        HlsMasterPlaylistResolver resolver =
                new HlsMasterPlaylistResolver(
                        null, new OrinunoHlsResolverConfig(1, 5_000L), fetcher);

        assertThatThrownBy(
                        () ->
                                resolver.resolve(
                                        "https://cdn/master.m3u8",
                                        MASTER_NESTED.getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("recursion exceeded depth=1");
    }

    @Test
    @DisplayName("resolve throws on non-#EXTM3U input")
    void rejectsInvalidManifest() {
        TrackingFetcher fetcher = new TrackingFetcher(Map.of());
        HlsMasterPlaylistResolver resolver = newResolver(fetcher);

        assertThatThrownBy(
                        () ->
                                resolver.resolve(
                                        "https://cdn/x.m3u8",
                                        "<html>Forbidden</html>".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("does not start with #EXTM3U");
    }

    @Test
    @DisplayName("absolutize handles protocol-relative + root-relative + relative URIs")
    void absolutizeShapes() {
        String base = "https://cdn.example/host/path/manifest.m3u8";
        assertThat(HlsMasterPlaylistResolver.absolutize(base, "https://other/file.ts"))
                .isEqualTo("https://other/file.ts");
        assertThat(HlsMasterPlaylistResolver.absolutize(base, "//other.example/seg.ts"))
                .isEqualTo("https://other.example/seg.ts");
        assertThat(HlsMasterPlaylistResolver.absolutize(base, "/abs/seg.ts"))
                .isEqualTo("https://cdn.example/abs/seg.ts");
        assertThat(HlsMasterPlaylistResolver.absolutize(base, "./rel/seg.ts"))
                .isEqualTo("https://cdn.example/host/path/rel/seg.ts");
        assertThat(HlsMasterPlaylistResolver.absolutize(base, "rel/seg.ts"))
                .isEqualTo("https://cdn.example/host/path/rel/seg.ts");
    }

    private HlsMasterPlaylistResolver newResolver(HlsManifestFetcher fetcher) {
        return new HlsMasterPlaylistResolver(
                null, new OrinunoHlsResolverConfig(3, 5_000L), fetcher);
    }

    private static final class TrackingFetcher implements HlsManifestFetcher {
        private final Map<String, byte[]> store;
        private final java.util.List<String> fetchedUrls = new java.util.ArrayList<>();

        private TrackingFetcher(Map<String, byte[]> store) {
            this.store = new HashMap<>(store);
        }

        @Override
        public byte[] fetch(String url) throws IOException {
            fetchedUrls.add(url);
            byte[] body = store.get(url);
            if (body == null) {
                throw new IOException("no fixture for " + url);
            }
            return body;
        }
    }
}
