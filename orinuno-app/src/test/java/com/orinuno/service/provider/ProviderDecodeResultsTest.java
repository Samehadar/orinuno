package com.orinuno.service.provider;

import static org.assertj.core.api.Assertions.assertThat;

import com.orinuno.aniboom.AniboomDecodeResult;
import com.orinuno.aniboom.AniboomErrorCodes;
import com.orinuno.jutsu.JutsuDecodeResult;
import com.orinuno.jutsu.JutsuErrorCodes;
import com.orinuno.sibnet.SibnetDecodeResult;
import com.orinuno.sibnet.SibnetErrorCodes;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ProviderDecodeResultsTest {

    @Test
    void jutsuSuccessIsCopiedShapeForShape() {
        Map<String, String> qualities = Map.of("720", "https://x/720.mp4");
        ProviderDecodeResult r =
                ProviderDecodeResults.from(JutsuDecodeResult.success(qualities, "video/mp4"));
        assertThat(r.success()).isTrue();
        assertThat(r.qualities()).isEqualTo(qualities);
        assertThat(r.format()).isEqualTo("video/mp4");
        assertThat(r.errorCode()).isNull();
    }

    @Test
    void jutsuFailurePreservesErrorCode() {
        ProviderDecodeResult r =
                ProviderDecodeResults.from(
                        JutsuDecodeResult.failure(JutsuErrorCodes.JUTSU_PREMIUM_REQUIRED));
        assertThat(r.success()).isFalse();
        assertThat(r.errorCode()).isEqualTo(JutsuErrorCodes.JUTSU_PREMIUM_REQUIRED);
        assertThat(r.qualities()).isEmpty();
    }

    @Test
    void sibnetSuccessIsCopiedShapeForShape() {
        Map<String, String> qualities = Map.of("720", "https://video.sibnet.ru/v/x.mp4");
        ProviderDecodeResult r =
                ProviderDecodeResults.from(SibnetDecodeResult.success(qualities, "video/mp4"));
        assertThat(r.success()).isTrue();
        assertThat(r.qualities()).isEqualTo(qualities);
        assertThat(r.format()).isEqualTo("video/mp4");
    }

    @Test
    void sibnetFailurePreservesErrorCode() {
        ProviderDecodeResult r =
                ProviderDecodeResults.from(
                        SibnetDecodeResult.failure(SibnetErrorCodes.SIBNET_VIDEO_NOT_FOUND));
        assertThat(r.success()).isFalse();
        assertThat(r.errorCode()).isEqualTo(SibnetErrorCodes.SIBNET_VIDEO_NOT_FOUND);
    }

    @Test
    void aniboomSuccessIsCopiedShapeForShape() {
        Map<String, String> qualities =
                Map.of("auto", "https://cdn/m.m3u8", "dash", "https://cdn/m.mpd");
        ProviderDecodeResult r =
                ProviderDecodeResults.from(
                        AniboomDecodeResult.success(qualities, "application/x-mpegURL"));
        assertThat(r.success()).isTrue();
        assertThat(r.qualities()).isEqualTo(qualities);
        assertThat(r.format()).isEqualTo("application/x-mpegURL");
    }

    @Test
    void aniboomFailurePreservesErrorCode() {
        ProviderDecodeResult r =
                ProviderDecodeResults.from(
                        AniboomDecodeResult.failure(AniboomErrorCodes.ANIBOOM_GEO_BLOCKED));
        assertThat(r.success()).isFalse();
        assertThat(r.errorCode()).isEqualTo(AniboomErrorCodes.ANIBOOM_GEO_BLOCKED);
    }
}
