package com.orinuno.service.provider.sibnet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.orinuno.service.provider.ProviderDecodeResult;
import com.orinuno.sibnet.SibnetClient;
import com.orinuno.sibnet.SibnetDecodeResult;
import com.orinuno.sibnet.SibnetErrorCodes;
import java.util.Map;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

/**
 * Wiring smoke test for the orinuno-app side of the sibnet-sdk extraction. Decoder behaviour is
 * fully covered in {@code com.orinuno.sibnet.decoder.SibnetDecoderTest} inside the SDK — this class
 * only verifies that the adapter translates {@link SibnetDecodeResult} → {@link
 * ProviderDecodeResult} faithfully on both overloads.
 */
class SibnetDecoderServiceAdapterTest {

    @Test
    void successResultIsMappedShapeForShape() {
        Map<String, String> qualities = Map.of("720", "https://video.sibnet.ru/v/x.mp4");
        SibnetClient client = mock(SibnetClient.class);
        when(client.decode(anyString()))
                .thenReturn(Mono.just(SibnetDecodeResult.success(qualities, "video/mp4")));
        SibnetDecoderService svc = new SibnetDecoderService(client);

        ProviderDecodeResult result =
                svc.decode("https://video.sibnet.ru/shell.php?videoid=1").block();
        assertThat(result).isNotNull();
        assertThat(result.success()).isTrue();
        assertThat(result.format()).isEqualTo("video/mp4");
        assertThat(result.qualities()).isEqualTo(qualities);
        assertThat(result.errorCode()).isNull();
    }

    @Test
    void numericIdOverloadIsAlsoAdapted() {
        SibnetClient client = mock(SibnetClient.class);
        when(client.decode(anyLong()))
                .thenReturn(
                        Mono.just(
                                SibnetDecodeResult.success(
                                        Map.of("720", "https://x/m.mp4"), "video/mp4")));
        SibnetDecoderService svc = new SibnetDecoderService(client);

        ProviderDecodeResult result = svc.decode(123L).block();
        assertThat(result).isNotNull();
        assertThat(result.success()).isTrue();
        assertThat(result.qualities()).containsEntry("720", "https://x/m.mp4");
    }

    @Test
    void failureErrorCodeIsPreservedVerbatim() {
        SibnetClient client = mock(SibnetClient.class);
        when(client.decode(anyString()))
                .thenReturn(
                        Mono.just(
                                SibnetDecodeResult.failure(
                                        SibnetErrorCodes.SIBNET_VIDEO_NOT_FOUND)));
        SibnetDecoderService svc = new SibnetDecoderService(client);

        ProviderDecodeResult result =
                svc.decode("https://video.sibnet.ru/shell.php?videoid=404").block();
        assertThat(result).isNotNull();
        assertThat(result.success()).isFalse();
        assertThat(result.errorCode()).isEqualTo(SibnetErrorCodes.SIBNET_VIDEO_NOT_FOUND);
        assertThat(result.qualities()).isEmpty();
    }
}
