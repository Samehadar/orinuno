package com.orinuno.service.provider;

import com.orinuno.aniboom.AniboomDecodeResult;
import com.orinuno.jutsu.JutsuDecodeResult;
import com.orinuno.sibnet.SibnetDecodeResult;

/**
 * Shape-for-shape translators from the per-provider SDK result records into orinuno-app's HTTP API
 * contract {@link ProviderDecodeResult}. Step 4 of the API/module split (ADR 0014) deletes the
 * {@code *DecoderService} adapter classes and lets controllers inject the SDK facades ({@code
 * JutsuClient}, {@code SibnetClient}, {@code AniboomClient}) directly. The single SDK→orinuno
 * mapping per provider used to live in the adapters; it now lives here so it stays testable in
 * isolation and we don't sprinkle the {@code if (success) ... else failure(...)} pattern across
 * every controller.
 *
 * <p>Note that {@link ProviderDecodeResult} is intentionally NOT exported to the SDKs — keeping it
 * inside orinuno-app means the SDKs do not depend on any orinuno-specific type. The duplication tax
 * (four shape-identical records) is the price we paid for the M3 standalone-SDK module layout (see
 * ADR 0012, ADR 0013).
 */
public final class ProviderDecodeResults {

    private ProviderDecodeResults() {}

    public static ProviderDecodeResult from(JutsuDecodeResult sdk) {
        if (sdk.success()) {
            return ProviderDecodeResult.success(sdk.qualities(), sdk.format());
        }
        return ProviderDecodeResult.failure(sdk.errorCode());
    }

    public static ProviderDecodeResult from(SibnetDecodeResult sdk) {
        if (sdk.success()) {
            return ProviderDecodeResult.success(sdk.qualities(), sdk.format());
        }
        return ProviderDecodeResult.failure(sdk.errorCode());
    }

    public static ProviderDecodeResult from(AniboomDecodeResult sdk) {
        if (sdk.success()) {
            return ProviderDecodeResult.success(sdk.qualities(), sdk.format());
        }
        return ProviderDecodeResult.failure(sdk.errorCode());
    }
}
