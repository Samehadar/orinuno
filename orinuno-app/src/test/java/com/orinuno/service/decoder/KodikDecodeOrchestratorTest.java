package com.orinuno.service.decoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.orinuno.configuration.OrinunoProperties;
import com.orinuno.service.KodikVideoDecoderService;
import com.orinuno.service.metrics.KodikDecoderMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/**
 * DECODE-8 — pins the orchestration semantics: regex-first, sniff-fallback only when (a) regex
 * returned empty/error AND (b) sniff fallback is enabled in config + Playwright is available.
 */
class KodikDecodeOrchestratorTest {

    private KodikVideoDecoderService regex;
    private PlaywrightSniffDecoder sniff;
    private OrinunoProperties properties;
    private KodikDecoderMetrics metrics;
    private KodikDecodeOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        regex = mock(KodikVideoDecoderService.class);
        sniff = mock(PlaywrightSniffDecoder.class);
        properties = new OrinunoProperties();
        properties.getDecoder().setSniffFallbackEnabled(true);
        metrics = new KodikDecoderMetrics(new SimpleMeterRegistry());
        orchestrator = new KodikDecodeOrchestrator(regex, sniff, properties, metrics);

        when(sniff.isAvailable()).thenReturn(true);
    }

    @Test
    @DisplayName("regex success short-circuits (sniff is never invoked)")
    void regexSuccessShortCircuits() {
        when(regex.decode("//k/1")).thenReturn(Mono.just(Map.of("720", "https://cdn/720.mp4")));

        StepVerifier.create(orchestrator.decode("//k/1"))
                .assertNext(
                        r -> {
                            assertThat(r.method()).isEqualTo(DecodeMethod.REGEX);
                            assertThat(r.qualities()).containsEntry("720", "https://cdn/720.mp4");
                        })
                .verifyComplete();

        verify(sniff, never()).sniff(eq("//k/1"));
    }

    @Test
    @DisplayName("regex empty + sniff success returns SNIFF result")
    void regexEmptyTriggersSniff() {
        when(regex.decode("//k/2")).thenReturn(Mono.just(Map.of()));
        when(sniff.sniff("//k/2"))
                .thenReturn(
                        Mono.just(
                                Map.of(
                                        PlaywrightSniffDecoder.SNIFF_QUALITY_KEY,
                                        "https://cdn/master.m3u8")));

        StepVerifier.create(orchestrator.decode("//k/2"))
                .assertNext(
                        r -> {
                            assertThat(r.method()).isEqualTo(DecodeMethod.SNIFF);
                            assertThat(r.qualities())
                                    .containsEntry(
                                            PlaywrightSniffDecoder.SNIFF_QUALITY_KEY,
                                            "https://cdn/master.m3u8");
                        })
                .verifyComplete();

        verify(sniff, times(1)).sniff(eq("//k/2"));
    }

    @Test
    @DisplayName("regex error + sniff success returns SNIFF result")
    void regexErrorTriggersSniff() {
        when(regex.decode("//k/3")).thenReturn(Mono.error(new RuntimeException("boom")));
        when(sniff.sniff("//k/3"))
                .thenReturn(
                        Mono.just(
                                Map.of(PlaywrightSniffDecoder.SNIFF_QUALITY_KEY, "https://cdn/x")));

        StepVerifier.create(orchestrator.decode("//k/3"))
                .assertNext(r -> assertThat(r.method()).isEqualTo(DecodeMethod.SNIFF))
                .verifyComplete();
    }

    @Test
    @DisplayName("regex empty + sniff also empty returns SNIFF empty (never errors out)")
    void bothEmptyReturnsSniffEmpty() {
        when(regex.decode("//k/4")).thenReturn(Mono.just(Map.of()));
        when(sniff.sniff("//k/4")).thenReturn(Mono.just(Map.of()));

        StepVerifier.create(orchestrator.decode("//k/4"))
                .assertNext(
                        r -> {
                            assertThat(r.method()).isEqualTo(DecodeMethod.SNIFF);
                            assertThat(r.isEmpty()).isTrue();
                        })
                .verifyComplete();
    }

    @Test
    @DisplayName("regex empty + sniff disabled by config returns REGEX empty (no sniff call)")
    void sniffDisabledByConfig() {
        properties.getDecoder().setSniffFallbackEnabled(false);
        when(regex.decode("//k/5")).thenReturn(Mono.just(Map.of()));

        StepVerifier.create(orchestrator.decode("//k/5"))
                .assertNext(
                        r -> {
                            assertThat(r.method()).isEqualTo(DecodeMethod.REGEX);
                            assertThat(r.isEmpty()).isTrue();
                        })
                .verifyComplete();

        verify(sniff, never()).sniff(eq("//k/5"));
    }

    @Test
    @DisplayName("regex empty + sniff unavailable returns REGEX empty (no sniff call)")
    void sniffUnavailableSkipsFallback() {
        when(sniff.isAvailable()).thenReturn(false);
        when(regex.decode("//k/6")).thenReturn(Mono.just(Map.of()));

        StepVerifier.create(orchestrator.decode("//k/6"))
                .assertNext(
                        r -> {
                            assertThat(r.method()).isEqualTo(DecodeMethod.REGEX);
                            assertThat(r.isEmpty()).isTrue();
                        })
                .verifyComplete();

        verify(sniff, never()).sniff(eq("//k/6"));
    }

    @Test
    @DisplayName(
            "Mono.empty() from regex decoder is treated as empty + falls through to sniff (DECODE-8"
                    + " defensive)")
    void emptyRegexMonoTreatedAsEmpty() {
        when(regex.decode("//k/7")).thenReturn(Mono.empty());
        when(sniff.sniff("//k/7")).thenReturn(Mono.just(Map.of()));

        StepVerifier.create(orchestrator.decode("//k/7"))
                .assertNext(
                        r -> {
                            assertThat(r.method()).isEqualTo(DecodeMethod.SNIFF);
                            assertThat(r.isEmpty()).isTrue();
                        })
                .verifyComplete();
    }

    @Test
    @DisplayName(
            "metrics record success/empty for both REGEX and SNIFF passes (DECODE-8 visibility)")
    void metricsRecordSuccessAndEmpty() {
        when(regex.decode("//k/8")).thenReturn(Mono.just(Map.of()));
        when(sniff.sniff("//k/8"))
                .thenReturn(
                        Mono.just(
                                Map.of(PlaywrightSniffDecoder.SNIFF_QUALITY_KEY, "https://cdn/x")));

        StepVerifier.create(orchestrator.decode("//k/8")).expectNextCount(1).verifyComplete();
    }
}
