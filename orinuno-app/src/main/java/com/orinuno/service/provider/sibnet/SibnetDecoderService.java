package com.orinuno.service.provider.sibnet;

import com.orinuno.service.provider.ProviderDecodeResult;
import com.orinuno.sibnet.SibnetClient;
import com.orinuno.sibnet.SibnetDecodeResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**
 * Thin Spring adapter on top of the standalone {@link SibnetClient} ({@code sibnet-sdk}). Existing
 * call sites (controllers, multi-source ranker) keep importing this class; the actual decode lives
 * in the SDK so it can be reused outside Spring Boot.
 *
 * <p>The two-overload surface ({@link #decode(long)} and {@link #decode(String)}) is preserved
 * intentionally — the multi-source ranker passes URLs while the catalog ingestion path passes
 * numeric ids. Translating a {@link SibnetDecodeResult} → {@link ProviderDecodeResult} is a 1:1
 * field copy.
 */
@Slf4j
@Service
public class SibnetDecoderService {

    private final SibnetClient client;

    public SibnetDecoderService(SibnetClient client) {
        this.client = client;
    }

    public Mono<ProviderDecodeResult> decode(long videoId) {
        return client.decode(videoId).map(SibnetDecoderService::toOrinuno);
    }

    public Mono<ProviderDecodeResult> decode(String shellUrl) {
        return client.decode(shellUrl).map(SibnetDecoderService::toOrinuno);
    }

    private static ProviderDecodeResult toOrinuno(SibnetDecodeResult sdk) {
        if (sdk.success()) {
            return ProviderDecodeResult.success(sdk.qualities(), sdk.format());
        }
        return ProviderDecodeResult.failure(sdk.errorCode());
    }
}
