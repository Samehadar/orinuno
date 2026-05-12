/*
 * KodikDecodeOrchestrator — ADR 0021 §D1b-2 (sniff-less).
 *
 * Routes through the regex-first decode pipeline. The sniff-fallback
 * branch documented in orinuno-app's KodikDecodeOrchestrator depends on
 * PlaywrightVideoFetcher + HLS helpers + KodikPlaywrightProperties, all
 * of which are queued for D1b-3 (Playwright stack port). Until then this
 * orchestrator only ever runs the REGEX path; sniff requests are
 * silently dropped, matching the legacy
 * orinuno.decoder.sniff-fallback-enabled=false default.
 *
 * When D1b-3 lands, this class regains the sniffDecoder field +
 * sniff-fallback branch unchanged; the {@code sniff-fallback-enabled}
 * knob (orinuno.source-kodik.decoder.sniff-fallback-enabled) gates the
 * branch at runtime.
 */
package com.orinuno.source.kodik.service.decoder;

import com.kodik.decoder.DecodeAttemptResult;
import com.kodik.decoder.DecodeMethod;
import com.kodik.decoder.KodikDecoderMetrics;
import com.orinuno.source.kodik.configuration.KodikDecoderProperties;
import com.orinuno.source.kodik.service.KodikVideoDecoderService;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Slf4j
@Component
public class KodikDecodeOrchestrator {

    private final KodikVideoDecoderService regexDecoder;
    private final KodikDecoderProperties decoderProperties;
    private final KodikDecoderMetrics decoderMetrics;

    public KodikDecodeOrchestrator(
            KodikVideoDecoderService regexDecoder,
            KodikDecoderProperties decoderProperties,
            KodikDecoderMetrics decoderMetrics) {
        this.regexDecoder = regexDecoder;
        this.decoderProperties = decoderProperties;
        this.decoderMetrics = decoderMetrics;
    }

    public Mono<DecodeAttemptResult> decode(String kodikLink) {
        return regexDecoder
                .decode(kodikLink)
                .map(qualities -> qualities == null ? Map.<String, String>of() : qualities)
                .defaultIfEmpty(Map.of())
                .onErrorResume(
                        ex -> {
                            log.warn(
                                    "DECODE-8: regex decoder errored for {}: {} — sniff fallback"
                                            + " queued for D1b-3, returning empty",
                                    kodikLink,
                                    ex.toString());
                            return Mono.just(Map.of());
                        })
                .map(
                        regexResult -> {
                            boolean ok = !regexResult.isEmpty();
                            recordMethod(DecodeMethod.REGEX, ok);
                            if (!ok && decoderProperties.isSniffFallbackEnabled()) {
                                log.debug(
                                        "DECODE-8: sniff fallback requested but Playwright stack"
                                                + " not yet ported (D1b-3); returning empty for {}",
                                        kodikLink);
                            }
                            return DecodeAttemptResult.regex(regexResult);
                        });
    }

    private void recordMethod(DecodeMethod method, boolean success) {
        if (decoderMetrics != null) {
            decoderMetrics.recordDecodeMethod(method.name(), success);
        }
    }
}
