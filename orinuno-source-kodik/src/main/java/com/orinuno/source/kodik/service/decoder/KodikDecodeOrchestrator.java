/*
 * KodikDecodeOrchestrator — ADR 0021 §D1b-3.
 *
 * DECODE-8 — orchestrates the regex-first / sniff-fallback decode
 * pipeline. Ported from orinuno-app/.../service/decoder/ with the full
 * sniff branch restored now that PlaywrightSniffDecoder + the Playwright
 * stack are present in source-kodik (D1b-3). Toggle via
 * orinuno.source-kodik.decoder.sniff-fallback-enabled (default false).
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
    private final PlaywrightSniffDecoder sniffDecoder;
    private final KodikDecoderProperties decoderProperties;
    private final KodikDecoderMetrics decoderMetrics;

    public KodikDecodeOrchestrator(
            KodikVideoDecoderService regexDecoder,
            PlaywrightSniffDecoder sniffDecoder,
            KodikDecoderProperties decoderProperties,
            KodikDecoderMetrics decoderMetrics) {
        this.regexDecoder = regexDecoder;
        this.sniffDecoder = sniffDecoder;
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
                                    "DECODE-8: regex decoder errored for {}: {} — handing off to"
                                            + " sniff fallback if enabled",
                                    kodikLink,
                                    ex.toString());
                            return Mono.just(Map.of());
                        })
                .flatMap(
                        regexResult -> {
                            if (!regexResult.isEmpty()) {
                                recordMethod(DecodeMethod.REGEX, true);
                                return Mono.just(DecodeAttemptResult.regex(regexResult));
                            }
                            recordMethod(DecodeMethod.REGEX, false);
                            if (!sniffEnabled()) {
                                log.debug(
                                        "DECODE-8: sniff fallback disabled (config), returning"
                                                + " empty regex result for {}",
                                        kodikLink);
                                return Mono.just(DecodeAttemptResult.regex(Map.of()));
                            }
                            return sniffDecoder
                                    .sniff(kodikLink)
                                    .map(
                                            sniffed -> {
                                                boolean ok = !sniffed.isEmpty();
                                                recordMethod(DecodeMethod.SNIFF, ok);
                                                if (ok) {
                                                    log.info(
                                                            "DECODE-8: sniff fallback recovered URL"
                                                                    + " for {}",
                                                            kodikLink);
                                                }
                                                return DecodeAttemptResult.sniff(sniffed);
                                            });
                        });
    }

    private boolean sniffEnabled() {
        return decoderProperties.isSniffFallbackEnabled() && sniffDecoder.isAvailable();
    }

    private void recordMethod(DecodeMethod method, boolean success) {
        if (decoderMetrics != null) {
            decoderMetrics.recordDecodeMethod(method.name(), success);
        }
    }
}
