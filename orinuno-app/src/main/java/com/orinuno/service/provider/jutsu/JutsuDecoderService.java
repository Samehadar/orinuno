package com.orinuno.service.provider.jutsu;

import com.orinuno.jutsu.JutsuClient;
import com.orinuno.jutsu.JutsuDecodeResult;
import com.orinuno.service.provider.ProviderDecodeResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**
 * Thin Spring adapter over {@link JutsuClient} (jutsu-sdk). Step 2 of the API/module split moved
 * the actual decoder to the SDK so we keep this class purely as a {@code @Service} bean orinuno-app
 * controllers can inject without learning the new package layout. The class name is intentionally
 * unchanged so existing call sites do not need updating.
 *
 * <p>Adapter responsibility is exactly two things:
 *
 * <ol>
 *   <li>Inject the SDK's {@link JutsuClient} as a bean (wired in {@code JutsuSdkConfiguration}).
 *   <li>Translate the SDK's {@link JutsuDecodeResult} back into orinuno's {@link
 *       ProviderDecodeResult}. The shapes are identical by design — the SDK keeps its own copy on
 *       purpose so the SDK does not depend on orinuno-app.
 * </ol>
 */
@Slf4j
@Service
public class JutsuDecoderService {

    private final JutsuClient client;

    public JutsuDecoderService(JutsuClient client) {
        this.client = client;
    }

    public Mono<ProviderDecodeResult> decode(String episodeUrl) {
        return client.decode(episodeUrl).map(JutsuDecoderService::toOrinuno);
    }

    private static ProviderDecodeResult toOrinuno(JutsuDecodeResult sdk) {
        if (sdk.success()) {
            return ProviderDecodeResult.success(sdk.qualities(), sdk.format());
        }
        return ProviderDecodeResult.failure(sdk.errorCode());
    }
}
