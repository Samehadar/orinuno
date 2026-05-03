package com.orinuno.service.provider.aniboom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.orinuno.aniboom.AniboomClient;
import com.orinuno.aniboom.AniboomDecodeResult;
import com.orinuno.aniboom.AniboomErrorCodes;
import com.orinuno.service.provider.ProviderDecodeResult;
import java.util.Map;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

/**
 * Wiring smoke test for the orinuno-app side of the aniboom-sdk extraction. Decoder behaviour is
 * fully covered in {@code com.orinuno.aniboom.decoder.AniboomDecoderTest} inside the SDK; this
 * class verifies the {@link AniboomDecodeResult} → {@link ProviderDecodeResult} translation is
 * field-for-field.
 */
class AniboomDecoderServiceAdapterTest {

    @Test
    void successResultIsMappedShapeForShape() {
        Map<String, String> qualities =
                Map.of("auto", "https://cdn/m.m3u8", "dash", "https://cdn/m.mpd");
        AniboomClient client = mock(AniboomClient.class);
        when(client.decode(anyString()))
                .thenReturn(
                        Mono.just(AniboomDecodeResult.success(qualities, "application/x-mpegURL")));
        AniboomDecoderService svc = new AniboomDecoderService(client);

        ProviderDecodeResult result = svc.decode("https://aniboom.one/embed/abc").block();
        assertThat(result).isNotNull();
        assertThat(result.success()).isTrue();
        assertThat(result.format()).isEqualTo("application/x-mpegURL");
        assertThat(result.qualities()).isEqualTo(qualities);
        assertThat(result.errorCode()).isNull();
    }

    @Test
    void failureErrorCodeIsPreservedVerbatim() {
        AniboomClient client = mock(AniboomClient.class);
        when(client.decode(anyString()))
                .thenReturn(
                        Mono.just(
                                AniboomDecodeResult.failure(
                                        AniboomErrorCodes.ANIBOOM_GEO_BLOCKED)));
        AniboomDecoderService svc = new AniboomDecoderService(client);

        ProviderDecodeResult result = svc.decode("https://aniboom.one/embed/abc").block();
        assertThat(result).isNotNull();
        assertThat(result.success()).isFalse();
        assertThat(result.errorCode()).isEqualTo(AniboomErrorCodes.ANIBOOM_GEO_BLOCKED);
        assertThat(result.qualities()).isEmpty();
    }
}
