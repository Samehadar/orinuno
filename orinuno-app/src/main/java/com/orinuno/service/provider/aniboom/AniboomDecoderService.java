package com.orinuno.service.provider.aniboom;

import com.orinuno.aniboom.AniboomClient;
import com.orinuno.aniboom.AniboomDecodeResult;
import com.orinuno.service.provider.ProviderDecodeResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**
 * Thin Spring adapter on top of the standalone {@link AniboomClient} ({@code aniboom-sdk}).
 * Existing call sites (controllers, multi-source ranker) keep importing this class; the actual
 * decode lives in the SDK so it can be reused outside Spring Boot.
 *
 * <p>Translating an {@link AniboomDecodeResult} → {@link ProviderDecodeResult} is a 1:1 field copy
 * — both are intentionally identical-shape records. The duplication is the price we pay for keeping
 * the SDKs free of orinuno-app types per the M3 module-split decision.
 */
@Slf4j
@Service
public class AniboomDecoderService {

    private final AniboomClient client;

    public AniboomDecoderService(AniboomClient client) {
        this.client = client;
    }

    public Mono<ProviderDecodeResult> decode(String embedUrl) {
        return client.decode(embedUrl).map(AniboomDecoderService::toOrinuno);
    }

    private static ProviderDecodeResult toOrinuno(AniboomDecodeResult sdk) {
        if (sdk.success()) {
            return ProviderDecodeResult.success(sdk.qualities(), sdk.format());
        }
        return ProviderDecodeResult.failure(sdk.errorCode());
    }
}
