package com.orinuno.jutsu.fallback;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.orinuno.configuration.JutsuLiveFallbackProperties;
import com.orinuno.jutsu.drift.JutsuDriftEvent;
import com.orinuno.jutsu.drift.JutsuDriftException;
import com.orinuno.jutsu.drift.JutsuDriftSignal;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/** Unit tests for the hybrid-fallback guards from ADR 0016. */
class JutsuLiveFallbackServiceTest {

    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

    @Test
    @DisplayName(
            "Cache miss with non-null upstream returns Optional.of(value); HIT counter increments")
    void cacheMissTriggersSdk() {
        JutsuLiveFallbackService svc = new JutsuLiveFallbackService(defaultProps(), meterRegistry);
        Optional<String> result =
                svc.dispatch("naruto", "ip:127.0.0.1", false, null, () -> "naruto-value");
        assertThat(result).contains("naruto-value");
        assertThat(svc.counterValue(JutsuLiveFallbackOutcome.HIT)).isEqualTo(1.0);
    }

    @Test
    @DisplayName(
            "Upstream null result puts slug in negative cache; second call returns 404 from cache")
    void negativeCacheBlocksRepeatMiss() {
        JutsuLiveFallbackService svc = new JutsuLiveFallbackService(defaultProps(), meterRegistry);
        AtomicInteger calls = new AtomicInteger();
        Optional<String> first =
                svc.dispatch(
                        "ghost",
                        "ip:1.2.3.4",
                        false,
                        null,
                        () -> {
                            calls.incrementAndGet();
                            return null;
                        });
        assertThat(first).isEmpty();
        assertThatExceptionOfType(JutsuLiveFallbackException.class)
                .isThrownBy(() -> svc.dispatch("ghost", "ip:1.2.3.4", false, null, () -> "x"))
                .matches(ex -> ex.status() == HttpStatus.NOT_FOUND)
                .matches(ex -> ex.outcome() == JutsuLiveFallbackOutcome.NEGATIVE_CACHE);
        assertThat(calls.get()).isEqualTo(1);
        assertThat(svc.counterValue(JutsuLiveFallbackOutcome.NEGATIVE_CACHE)).isEqualTo(1.0);
    }

    @Test
    @DisplayName("Rate-limit exhausted → 429 with Retry-After")
    void rateLimitReturns429() {
        JutsuLiveFallbackService svc =
                new JutsuLiveFallbackService(strictRateLimit(), meterRegistry);
        // Single consumer key — bucket capacity = 1 token per 5 seconds.
        svc.dispatch("a", "consumer-A", false, null, () -> "ok-1");
        assertThatExceptionOfType(JutsuLiveFallbackException.class)
                .isThrownBy(() -> svc.dispatch("b", "consumer-A", false, null, () -> "ok-2"))
                .matches(ex -> ex.status() == HttpStatus.TOO_MANY_REQUESTS)
                .matches(ex -> ex.retryAfterSeconds() >= 1);
        assertThat(svc.counterValue(JutsuLiveFallbackOutcome.RATE_LIMITED)).isEqualTo(1.0);
    }

    @Test
    @DisplayName("Kill-switch disabled → 404 with disabled outcome, upstream never called")
    void killSwitchDisabledReturns404() {
        JutsuLiveFallbackProperties props =
                new JutsuLiveFallbackProperties(
                        false,
                        new JutsuLiveFallbackProperties.RateLimit(),
                        new JutsuLiveFallbackProperties.NegativeCache(),
                        new JutsuLiveFallbackProperties.Buckets());
        JutsuLiveFallbackService svc = new JutsuLiveFallbackService(props, meterRegistry);
        AtomicInteger calls = new AtomicInteger();
        assertThatExceptionOfType(JutsuLiveFallbackException.class)
                .isThrownBy(
                        () ->
                                svc.dispatch(
                                        "x",
                                        "consumer",
                                        false,
                                        null,
                                        () -> {
                                            calls.incrementAndGet();
                                            return "noop";
                                        }))
                .matches(ex -> ex.status() == HttpStatus.NOT_FOUND);
        assertThat(calls.get()).isZero();
        assertThat(svc.counterValue(JutsuLiveFallbackOutcome.DISABLED)).isEqualTo(1.0);
    }

    @Test
    @DisplayName("refresh=true without X-API-KEY → 401 (DISABLED outcome)")
    void refreshRequiresApiKey() {
        JutsuLiveFallbackService svc = new JutsuLiveFallbackService(defaultProps(), meterRegistry);
        assertThatExceptionOfType(JutsuLiveFallbackException.class)
                .isThrownBy(() -> svc.dispatch("naruto", "anon", true, null, () -> "x"))
                .matches(ex -> ex.status() == HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("refresh=true bypasses negative cache when X-API-KEY is present")
    void refreshBypassesNegativeCache() {
        JutsuLiveFallbackService svc = new JutsuLiveFallbackService(defaultProps(), meterRegistry);
        svc.dispatch("naruto", "consumer", false, null, () -> null);
        Optional<String> result = svc.dispatch("naruto", "consumer-2", true, "k1", () -> "fresh");
        assertThat(result).contains("fresh");
    }

    @Test
    @DisplayName("Outcome counter increments uniquely per outcome (sanity)")
    void metricsRecordOutcomes() {
        JutsuLiveFallbackService svc = new JutsuLiveFallbackService(defaultProps(), meterRegistry);
        svc.dispatch("a", "consumer", false, null, () -> "ok");
        assertThat(svc.counterValue(JutsuLiveFallbackOutcome.HIT)).isEqualTo(1.0);
        assertThat(svc.counterValue(JutsuLiveFallbackOutcome.MISS)).isZero();
    }

    // -------------------------------------------------------------------------
    // ADR 0016 §"Transient vs permanent" — error classification
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Upstream HTTP 404 → negative cache, return empty")
    void http404IsNegativelyCached() {
        JutsuLiveFallbackService svc = new JutsuLiveFallbackService(defaultProps(), meterRegistry);
        Optional<String> result =
                svc.dispatch(
                        "missing",
                        "consumer",
                        false,
                        null,
                        () -> {
                            throw notFound();
                        });
        assertThat(result).isEmpty();
        assertThat(svc.counterValue(JutsuLiveFallbackOutcome.MISS)).isEqualTo(1.0);
        // Second call must short-circuit on the negative cache.
        AtomicInteger second = new AtomicInteger();
        assertThatExceptionOfType(JutsuLiveFallbackException.class)
                .isThrownBy(
                        () ->
                                svc.dispatch(
                                        "missing",
                                        "consumer",
                                        false,
                                        null,
                                        () -> {
                                            second.incrementAndGet();
                                            return "x";
                                        }))
                .matches(ex -> ex.outcome() == JutsuLiveFallbackOutcome.NEGATIVE_CACHE);
        assertThat(second.get()).isZero();
    }

    @Test
    @DisplayName("Upstream HTTP 5xx → upstream-error 502, slug NOT cached")
    void http5xxBecomesUpstreamError() {
        JutsuLiveFallbackService svc = new JutsuLiveFallbackService(defaultProps(), meterRegistry);
        assertThatExceptionOfType(JutsuLiveFallbackException.class)
                .isThrownBy(
                        () ->
                                svc.dispatch(
                                        "naruto",
                                        "consumer",
                                        false,
                                        null,
                                        () -> {
                                            throw serverError();
                                        }))
                .matches(ex -> ex.status() == HttpStatus.BAD_GATEWAY)
                .matches(ex -> ex.outcome() == JutsuLiveFallbackOutcome.UPSTREAM_ERROR);
        // Second call must NOT be short-circuited from negative cache (slug not poisoned).
        AtomicInteger second = new AtomicInteger();
        Optional<String> result =
                svc.dispatch(
                        "naruto",
                        "consumer-other",
                        false,
                        null,
                        () -> {
                            second.incrementAndGet();
                            return "ok";
                        });
        assertThat(result).contains("ok");
        assertThat(second.get()).isOne();
    }

    @Test
    @DisplayName("JutsuDriftException → upstream-error 502, slug NOT cached")
    void driftBecomesUpstreamError() {
        JutsuLiveFallbackService svc = new JutsuLiveFallbackService(defaultProps(), meterRegistry);
        JutsuDriftException drift =
                new JutsuDriftException(
                        JutsuDriftEvent.of(JutsuDriftSignal.SCHEMA_VIOLATION, "live", "boom"));
        assertThatExceptionOfType(JutsuLiveFallbackException.class)
                .isThrownBy(
                        () ->
                                svc.dispatch(
                                        "naruto",
                                        "consumer",
                                        false,
                                        null,
                                        () -> {
                                            throw drift;
                                        }))
                .matches(ex -> ex.outcome() == JutsuLiveFallbackOutcome.UPSTREAM_ERROR);
    }

    @Test
    @DisplayName("Network IOException wrapped in RuntimeException → upstream-error, NOT cached")
    void ioExceptionIsTransient() {
        JutsuLiveFallbackService svc = new JutsuLiveFallbackService(defaultProps(), meterRegistry);
        assertThatExceptionOfType(JutsuLiveFallbackException.class)
                .isThrownBy(
                        () ->
                                svc.dispatch(
                                        "naruto",
                                        "consumer",
                                        false,
                                        null,
                                        () -> {
                                            throw new RuntimeException(
                                                    new SocketTimeoutException("read timed out"));
                                        }))
                .matches(ex -> ex.outcome() == JutsuLiveFallbackOutcome.UPSTREAM_ERROR);
        // Untouched negative cache: another caller for the same slug should still hit upstream.
        Optional<String> result = svc.dispatch("naruto", "other", false, null, () -> "ok");
        assertThat(result).contains("ok");
    }

    @Test
    @DisplayName("Generic IOException at top level → upstream-error")
    void genericIoExceptionIsTransient() {
        JutsuLiveFallbackService svc = new JutsuLiveFallbackService(defaultProps(), meterRegistry);
        assertThatExceptionOfType(JutsuLiveFallbackException.class)
                .isThrownBy(
                        () ->
                                svc.dispatch(
                                        "naruto",
                                        "consumer",
                                        false,
                                        null,
                                        () -> {
                                            throw new RuntimeException(new IOException("connect"));
                                        }))
                .matches(ex -> ex.outcome() == JutsuLiveFallbackOutcome.UPSTREAM_ERROR);
    }

    // -------------------------------------------------------------------------
    // Reactive variant
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("dispatchReactive: HIT path emits Optional.of(value)")
    void reactiveHit() {
        JutsuLiveFallbackService svc = new JutsuLiveFallbackService(defaultProps(), meterRegistry);
        StepVerifier.create(
                        svc.dispatchReactive(
                                "naruto", "consumer", false, null, () -> Mono.just("naruto-value")))
                .expectNextMatches(opt -> opt.isPresent() && "naruto-value".equals(opt.get()))
                .verifyComplete();
    }

    @Test
    @DisplayName("dispatchReactive: empty Mono → negative cache + Optional.empty()")
    void reactiveEmptyToNegative() {
        JutsuLiveFallbackService svc = new JutsuLiveFallbackService(defaultProps(), meterRegistry);
        StepVerifier.create(
                        svc.dispatchReactive("ghost", "consumer", false, null, () -> Mono.empty()))
                .expectNextMatches(Optional::isEmpty)
                .verifyComplete();
        // Second call short-circuits.
        StepVerifier.create(
                        svc.dispatchReactive(
                                "ghost", "consumer", false, null, () -> Mono.just("x")))
                .expectErrorMatches(
                        ex ->
                                ex instanceof JutsuLiveFallbackException
                                        && ((JutsuLiveFallbackException) ex).outcome()
                                                == JutsuLiveFallbackOutcome.NEGATIVE_CACHE)
                .verify();
    }

    @Test
    @DisplayName("dispatchReactive: 5xx → upstream-error, slug NOT cached")
    void reactiveTransientUpstreamError() {
        JutsuLiveFallbackService svc = new JutsuLiveFallbackService(defaultProps(), meterRegistry);
        StepVerifier.create(
                        svc.dispatchReactive(
                                "naruto", "consumer", false, null, () -> Mono.error(serverError())))
                .expectErrorMatches(
                        ex ->
                                ex instanceof JutsuLiveFallbackException
                                        && ((JutsuLiveFallbackException) ex).status()
                                                == HttpStatus.BAD_GATEWAY)
                .verify();
        // Slug not poisoned: another call hits upstream again.
        StepVerifier.create(
                        svc.dispatchReactive("naruto", "other", false, null, () -> Mono.just("ok")))
                .expectNextMatches(opt -> opt.isPresent() && "ok".equals(opt.get()))
                .verifyComplete();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static JutsuLiveFallbackProperties defaultProps() {
        return new JutsuLiveFallbackProperties(
                true,
                new JutsuLiveFallbackProperties.RateLimit(5.0),
                new JutsuLiveFallbackProperties.NegativeCache(24),
                new JutsuLiveFallbackProperties.Buckets());
    }

    private static JutsuLiveFallbackProperties strictRateLimit() {
        return new JutsuLiveFallbackProperties(
                true,
                new JutsuLiveFallbackProperties.RateLimit(0.2),
                new JutsuLiveFallbackProperties.NegativeCache(),
                new JutsuLiveFallbackProperties.Buckets());
    }

    private static WebClientResponseException notFound() {
        return WebClientResponseException.create(
                404, "Not Found", HttpHeaders.EMPTY, new byte[0], null);
    }

    private static WebClientResponseException serverError() {
        return WebClientResponseException.create(
                503, "Service Unavailable", HttpHeaders.EMPTY, new byte[0], null);
    }
}
