package com.orinuno.service.provider.jutsu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.orinuno.jutsu.JutsuClient;
import com.orinuno.jutsu.JutsuDecodeResult;
import com.orinuno.jutsu.JutsuErrorCodes;
import com.orinuno.service.provider.ProviderDecodeResult;
import java.util.Map;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

/**
 * Wiring smoke test for the orinuno-app side of the jutsu-sdk extraction. The actual decode logic
 * lives in {@code com.orinuno.jutsu.decoder.JutsuDecoderTest} inside the SDK; here we only verify
 * the {@link JutsuDecoderService} adapter faithfully translates {@link JutsuDecodeResult} into the
 * orinuno-local {@link ProviderDecodeResult}.
 */
class JutsuDecoderServiceAdapterTest {

    @Test
    void successResultIsMappedShapeForShape() {
        Map<String, String> qualities = Map.of("720", "https://x/720.mp4");
        JutsuClient client = mock(JutsuClient.class);
        when(client.decode(anyString()))
                .thenReturn(Mono.just(JutsuDecodeResult.success(qualities, "video/mp4")));
        JutsuDecoderService svc = new JutsuDecoderService(client);

        ProviderDecodeResult result = svc.decode("https://jut.su/x/episode-1.html").block();
        assertThat(result).isNotNull();
        assertThat(result.success()).isTrue();
        assertThat(result.format()).isEqualTo("video/mp4");
        assertThat(result.qualities()).isEqualTo(qualities);
        assertThat(result.errorCode()).isNull();
    }

    @Test
    void failureErrorCodeIsPreservedVerbatim() {
        // Critical contract: operators grep PROVIDER_DECODE error codes in alerts. Renaming or
        // dropping them in the adapter would silently break runbook routing.
        JutsuClient client = mock(JutsuClient.class);
        when(client.decode(anyString()))
                .thenReturn(
                        Mono.just(
                                JutsuDecodeResult.failure(JutsuErrorCodes.JUTSU_PREMIUM_REQUIRED)));
        JutsuDecoderService svc = new JutsuDecoderService(client);

        ProviderDecodeResult result = svc.decode("https://jut.su/x/episode-1.html").block();
        assertThat(result).isNotNull();
        assertThat(result.success()).isFalse();
        assertThat(result.errorCode()).isEqualTo(JutsuErrorCodes.JUTSU_PREMIUM_REQUIRED);
        assertThat(result.qualities()).isEmpty();
    }
}
