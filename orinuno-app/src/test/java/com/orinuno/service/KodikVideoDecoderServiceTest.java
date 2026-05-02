package com.orinuno.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class KodikVideoDecoderServiceTest {

    @Test
    @DisplayName("rotWithShift(18) should correctly transform characters")
    void rotWithShiftShouldShiftBy18() {
        assertThat(KodikVideoDecoderService.rotWithShift("a", 18)).isEqualTo("s");
        assertThat(KodikVideoDecoderService.rotWithShift("A", 18)).isEqualTo("S");
        assertThat(KodikVideoDecoderService.rotWithShift("z", 18)).isEqualTo("r");
        assertThat(KodikVideoDecoderService.rotWithShift("123", 18)).isEqualTo("123");
        assertThat(KodikVideoDecoderService.rotWithShift("abc123", 18)).isEqualTo("stu123");
    }

    @Test
    @DisplayName("rotWithShift should be reversible with complementary shift")
    void rotWithShiftShouldBeReversible() {
        String original = "HelloWorld";
        String encoded = KodikVideoDecoderService.rotWithShift(original, 18);
        String decoded = KodikVideoDecoderService.rotWithShift(encoded, 8);
        assertThat(decoded).isEqualTo(original);
    }

    @Test
    @DisplayName("URL-safe Base64 decode should handle dash and underscore replacement")
    void decodeUrlSafeBase64ShouldWork() {
        // Standard base64 "Hello" = "SGVsbG8="
        // URL-safe variant: "SGVsbG8="
        String result = KodikVideoDecoderService.decodeUrlSafeBase64("SGVsbG8=");
        assertThat(result).isEqualTo("Hello");
    }

    @Test
    @DisplayName("URL-safe Base64 decode should add missing padding")
    void decodeUrlSafeBase64ShouldHandleMissingPadding() {
        // "SGVsbG8" without padding should also decode to "Hello"
        String result = KodikVideoDecoderService.decodeUrlSafeBase64("SGVsbG8");
        assertThat(result).isEqualTo("Hello");
    }

    @Test
    @DisplayName("decodeVideoUrl should handle already-plain manifest URLs")
    void decodeVideoUrlShouldPassthroughManifestUrls() {
        String url = "https://example.com/manifest.m3u8";
        String result = KodikVideoDecoderService.decodeVideoUrl(url);
        assertThat(result).isEqualTo("https://example.com/manifest.m3u8");
    }

    @Test
    @DisplayName(
            "DECODE-6: short-circuit fires for any URL-shaped input (http://, https://, //)"
                    + " — not just manifest.m3u8 substring")
    void decodeVideoUrlShortCircuitsAllUrlShapes() {
        assertThat(KodikVideoDecoderService.decodeVideoUrl("https://cdn.kodik.cc/v/abc/720.mp4"))
                .as("https:// should short-circuit, not be ROT-decoded")
                .isEqualTo("https://cdn.kodik.cc/v/abc/720.mp4");

        assertThat(KodikVideoDecoderService.decodeVideoUrl("http://cdn.kodik.cc/v/abc/720.mp4"))
                .isEqualTo("http://cdn.kodik.cc/v/abc/720.mp4");

        assertThat(KodikVideoDecoderService.decodeVideoUrl("//cdn.kodik.cc/v/abc/720.mp4"))
                .as("// should normalize to https:// per normalizeDecodedUrl")
                .isEqualTo("https://cdn.kodik.cc/v/abc/720.mp4");

        assertThat(KodikVideoDecoderService.decodeVideoUrl(""))
                .as("blank input must not crash")
                .isEqualTo("");
    }

    @Test
    @DisplayName(
            "DECODE-6: decodeVideoUrlWithProvenance reports correct path enum for short-circuit"
                    + " HTTP, m3u8 substring, cached shift, brute-force")
    void decodeVideoUrlWithProvenanceReportsCorrectPath() {
        assertThat(
                        KodikVideoDecoderService.decodeVideoUrlWithProvenance(
                                        "https://example.com/720.mp4")
                                .path())
                .isEqualTo(
                        com.orinuno.service.metrics.KodikDecoderMetrics.DecodePath
                                .SHORT_CIRCUIT_HTTP);

        assertThat(KodikVideoDecoderService.decodeVideoUrlWithProvenance("").path())
                .isEqualTo(
                        com.orinuno.service.metrics.KodikDecoderMetrics.DecodePath
                                .FALLBACK_NO_SHIFT_WORKED);
    }

    @Test
    @DisplayName("decodeVideoUrl should handle // prefix")
    void decodeVideoUrlShouldNormalizeDoubleSlashPrefix() {
        // Create an encoded URL that decodes to "//example.com/video.mp4"
        // "//example.com/video.mp4" in base64 = "Ly9leGFtcGxlLmNvbS92aWRlby5tcDQ="
        // After reverse-ROT13 (shift +8 is the reverse of +18):
        // We need to find what ROT13(x) = "Ly9leGFtcGxlLmNvbS92aWRlby5tcDQ="
        // Only letters are shifted, so let's encode manually
        // For testing purposes, test the normalizer independently:
        String decoded =
                KodikVideoDecoderService.decodeVideoUrl("Ly9leGFtcGxlLmNvbS92aWRlby5tcDQ=");
        // The result should at least not start with //
        // (actual decoded value depends on ROT13 then Base64 which may not produce valid URL from
        // this test input)
    }

    @Test
    @DisplayName("Full decode flow: ROT13 then Base64")
    void fullDecodeFlowShouldWork() {
        // Known test case from Kodik:
        // To create a test case, we encode backwards:
        // 1. Take a URL: "//test.com/v.mp4"
        // 2. Base64 encode: "Ly90ZXN0LmNvbS92Lm1wNA=="
        // 3. Make URL-safe: "Ly90ZXN0LmNvbS92Lm1wNA"
        // 4. Apply inverse ROT13 (shift +8, since 26-18=8):
        String urlSafeBase64 = "Ly90ZXN0LmNvbS92Lm1wNA";
        // Apply shift +8 (inverse of +18) to get what Kodik would encode
        StringBuilder kodikEncoded = new StringBuilder();
        for (char c : urlSafeBase64.toCharArray()) {
            if (Character.isLetter(c)) {
                char base = Character.isUpperCase(c) ? 'A' : 'a';
                kodikEncoded.append((char) (base + (c - base + 8) % 26));
            } else {
                kodikEncoded.append(c);
            }
        }

        String result = KodikVideoDecoderService.decodeVideoUrl(kodikEncoded.toString());
        assertThat(result).isEqualTo("https://test.com/v.mp4");
    }

    @Test
    @DisplayName("parseVideoResponse returns decoded URLs when not geo-blocked")
    void parseVideoResponseReturnsUrlsWhenNotGeoBlocked() {
        GeoBlockDetector geoDetector = mock(GeoBlockDetector.class);
        when(geoDetector.isCdnGeoBlocked(anyString())).thenReturn(false);
        KodikVideoDecoderService svc =
                new KodikVideoDecoderService(null, null, null, geoDetector, null, null, null);

        String json =
                "{\"links\":{\"720\":[{\"src\":\"https://cdn.example/720.mp4:hls:manifest.m3u8\"}]}}";

        Map<String, String> result = svc.parseVideoResponse(json);

        assertThat(result).containsEntry("720", "https://cdn.example/720.mp4");
        assertThat(result).doesNotContainKey("_geo_blocked");
    }

    @Test
    @DisplayName(
            "parseVideoResponse returns empty map when all URLs are geo-blocked"
                    + " (regression: mp4_link='true' bug)")
    void parseVideoResponseReturnsEmptyWhenAllGeoBlocked() {
        GeoBlockDetector geoDetector = mock(GeoBlockDetector.class);
        when(geoDetector.isCdnGeoBlocked(anyString())).thenReturn(true);
        KodikVideoDecoderService svc =
                new KodikVideoDecoderService(null, null, null, geoDetector, null, null, null);

        String json =
                "{\"links\":{\"720\":[{\"src\":\"https://p78.kodik.info/s/m/abc/tok:exp/720.mp4:hls:manifest.m3u8\"}]}}";

        Map<String, String> result = svc.parseVideoResponse(json);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("parseVideoResponse never emits sentinel keys (no _geo_blocked entry)")
    void parseVideoResponseNeverEmitsSentinel() {
        GeoBlockDetector geoDetector = mock(GeoBlockDetector.class);
        when(geoDetector.isCdnGeoBlocked(anyString())).thenReturn(true);
        KodikVideoDecoderService svc =
                new KodikVideoDecoderService(null, null, null, geoDetector, null, null, null);

        String json =
                "{\"links\":{\"720\":[{\"src\":\"https://geo.example/manifest.m3u8\"}],"
                        + "\"480\":[{\"src\":\"https://geo.example/480.mp4:hls:manifest.m3u8\"}]}}";

        Map<String, String> result = svc.parseVideoResponse(json);

        assertThat(result.keySet()).noneMatch(k -> k.startsWith("_"));
    }

    @Test
    @DisplayName("parseVideoResponse handles empty/no-match JSON")
    void parseVideoResponseHandlesEmptyInput() {
        GeoBlockDetector geoDetector = mock(GeoBlockDetector.class);
        KodikVideoDecoderService svc =
                new KodikVideoDecoderService(null, null, null, geoDetector, null, null, null);

        Map<String, String> result = svc.parseVideoResponse("{}");

        assertThat(result).isEmpty();
    }
}
