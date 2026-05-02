package com.orinuno.service.decoder;

import com.orinuno.configuration.OrinunoProperties;
import com.orinuno.service.KodikVideoDecoderService;
import com.orinuno.service.metrics.KodikDecoderMetrics;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * DECODE-8 — orchestrates the regex-first / sniff-fallback decode pipeline.
 *
 * <p>Until DECODE-8 every variant was decoded via the regex/JS-based {@link
 * KodikVideoDecoderService}. When Kodik shipped a fresh player JS bundle that broke the regex (see
 * the {@code app.serial.*.js} hot-fix in May 2026), every decode failed silently until we patched
 * the regex. DECODE-8 keeps the fast regex path as the primary decoder but ALSO wires in a
 * Playwright-backed network-sniff fallback ({@link PlaywrightSniffDecoder}) so we keep producing
 * URLs while the regex layer is broken.
 *
 * <p>The orchestrator returns a {@link DecodeAttemptResult} carrying both the URL map and the
 * {@link DecodeMethod} discriminator so the caller (currently {@link
 * com.orinuno.service.ParserService}) persists the {@code decode_method} column. Disabled-by-flag
 * via {@code orinuno.decoder.sniff-fallback-enabled=false} so deployments that don't have
 * Playwright wired up never pay the latency penalty.
 */
@Slf4j
@Component
public class KodikDecodeOrchestrator {

    private final KodikVideoDecoderService regexDecoder;
    private final PlaywrightSniffDecoder sniffDecoder;
    private final OrinunoProperties properties;
    private final KodikDecoderMetrics decoderMetrics;

    public KodikDecodeOrchestrator(
            KodikVideoDecoderService regexDecoder,
            PlaywrightSniffDecoder sniffDecoder,
            OrinunoProperties properties,
            KodikDecoderMetrics decoderMetrics) {
        this.regexDecoder = regexDecoder;
        this.sniffDecoder = sniffDecoder;
        this.properties = properties;
        this.decoderMetrics = decoderMetrics;
    }

    /**
     * Try the regex decoder first; on empty result OR unhandled error, fall back to the sniff
     * decoder if enabled + available. Always emits a {@link DecodeAttemptResult} (never errors out)
     * so callers can drive their own retry / outcome bookkeeping uniformly.
     */
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
        return properties.getDecoder().isSniffFallbackEnabled() && sniffDecoder.isAvailable();
    }

    private void recordMethod(DecodeMethod method, boolean success) {
        if (decoderMetrics != null) {
            decoderMetrics.recordDecodeMethod(method.name(), success);
        }
    }
}
