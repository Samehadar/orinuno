package com.orinuno.jutsu.drift;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class JutsuDriftDetectorTest {

    @Test
    void newDetectorIsHealthy() {
        JutsuDriftDetector detector = new JutsuDriftDetector();

        JutsuDriftSnapshot snap = detector.snapshot();

        assertThat(snap.health()).isEqualTo(JutsuDriftHealth.HEALTHY);
        assertThat(snap.lifetimeEvents()).isZero();
        assertThat(snap.eventsInWindow()).isZero();
        assertThat(snap.recentEvents()).isEmpty();
    }

    @Test
    void observeSurfacesInSnapshot() {
        JutsuDriftDetector detector = new JutsuDriftDetector();
        JutsuDriftEvent event = JutsuDriftEvent.of(JutsuDriftSignal.NEW_CSS_CLASS, "test", "x");

        detector.observe(event);
        JutsuDriftSnapshot snap = detector.snapshot();

        assertThat(snap.lifetimeEvents()).isEqualTo(1);
        assertThat(snap.eventsInWindow()).isEqualTo(1);
        assertThat(snap.recentEvents()).containsExactly(event);
        assertThat(snap.eventsBySignalInWindow()).containsEntry(JutsuDriftSignal.NEW_CSS_CLASS, 1);
    }

    @Test
    void slidingWindowEvictsOldestBeyondCap() {
        // Tiny window so we don't have to allocate 200 events to prove eviction.
        JutsuDriftDetector detector = new JutsuDriftDetector(3, 1, 1, Clock.systemUTC());

        for (int i = 0; i < 5; i++) {
            detector.observe(
                    JutsuDriftEvent.of(JutsuDriftSignal.NEW_CSS_CLASS, "test", "evt-" + i));
        }
        JutsuDriftSnapshot snap = detector.snapshot();

        assertThat(snap.eventsInWindow()).isEqualTo(3);
        assertThat(snap.recentEvents())
                .extracting(JutsuDriftEvent::detail)
                .containsExactly("evt-2", "evt-3", "evt-4");
        assertThat(snap.lifetimeEvents()).isEqualTo(5);
    }

    @Test
    void selectorMissPromotesToDegraded() {
        JutsuDriftDetector detector = new JutsuDriftDetector();

        detector.observe(
                JutsuDriftEvent.selectorMiss("JutsuCatalogParser", ".foo", "missing card"));

        assertThat(detector.snapshot().health()).isEqualTo(JutsuDriftHealth.DEGRADED);
    }

    @Test
    void unknownTemplatePromotesToDegraded() {
        JutsuDriftDetector detector = new JutsuDriftDetector();

        detector.observe(
                JutsuDriftEvent.of(
                        JutsuDriftSignal.UNKNOWN_TEMPLATE, "JutsuClient", "page is /captcha/"));

        assertThat(detector.snapshot().health()).isEqualTo(JutsuDriftHealth.DEGRADED);
    }

    @Test
    void unexpectedHttpStatusPromotesToUnavailable() {
        JutsuDriftDetector detector = new JutsuDriftDetector();

        detector.observe(
                JutsuDriftEvent.of(
                        JutsuDriftSignal.UNEXPECTED_HTTP_STATUS,
                        "JutsuCatalogClient",
                        "503 from /anime/"));

        assertThat(detector.snapshot().health()).isEqualTo(JutsuDriftHealth.UNAVAILABLE);
    }

    @Test
    void emptyResponsePromotesToUnavailable() {
        JutsuDriftDetector detector = new JutsuDriftDetector();

        detector.observe(
                JutsuDriftEvent.of(
                        JutsuDriftSignal.EMPTY_RESPONSE, "JutsuAnimeInfoClient", "0-byte body"));

        assertThat(detector.snapshot().health()).isEqualTo(JutsuDriftHealth.UNAVAILABLE);
    }

    @Test
    void unavailableSignalAgesOutOfCriticalLookback() {
        // Critical lookback = 2: only the last 2 events count for UNAVAILABLE.
        JutsuDriftDetector detector = new JutsuDriftDetector(10, 2, 100, Clock.systemUTC());
        detector.observe(
                JutsuDriftEvent.of(
                        JutsuDriftSignal.UNEXPECTED_HTTP_STATUS, "JutsuCatalogClient", "503"));
        // Two non-critical events push the 503 past the lookback window.
        detector.observe(JutsuDriftEvent.of(JutsuDriftSignal.NEW_CSS_CLASS, "p", "a"));
        detector.observe(JutsuDriftEvent.of(JutsuDriftSignal.NEW_CSS_CLASS, "p", "b"));

        JutsuDriftSnapshot snap = detector.snapshot();

        assertThat(snap.health())
                .as(
                        "503 has aged past the 2-event lookback so we should not be UNAVAILABLE"
                                + " any more")
                .isNotEqualTo(JutsuDriftHealth.UNAVAILABLE);
    }

    @Test
    void degradedThresholdCountsTotalEventsInWindow() {
        // threshold=3 → exactly 3 noisy events should trigger DEGRADED.
        JutsuDriftDetector detector = new JutsuDriftDetector(10, 1, 3, Clock.systemUTC());
        detector.observe(JutsuDriftEvent.of(JutsuDriftSignal.NEW_CSS_CLASS, "p", "a"));
        detector.observe(JutsuDriftEvent.of(JutsuDriftSignal.NEW_CSS_CLASS, "p", "b"));

        assertThat(detector.snapshot().health()).isEqualTo(JutsuDriftHealth.HEALTHY);

        detector.observe(JutsuDriftEvent.of(JutsuDriftSignal.NEW_CSS_CLASS, "p", "c"));

        assertThat(detector.snapshot().health()).isEqualTo(JutsuDriftHealth.DEGRADED);
    }

    @Test
    void resetClearsWindowAndCounters() {
        JutsuDriftDetector detector = new JutsuDriftDetector();
        detector.observe(JutsuDriftEvent.of(JutsuDriftSignal.NEW_CSS_CLASS, "p", "a"));
        detector.observe(JutsuDriftEvent.of(JutsuDriftSignal.SELECTOR_MISS, "p", "b"));

        detector.reset();
        JutsuDriftSnapshot snap = detector.snapshot();

        assertThat(snap.lifetimeEvents()).isZero();
        assertThat(snap.eventsInWindow()).isZero();
        assertThat(snap.health()).isEqualTo(JutsuDriftHealth.HEALTHY);
        assertThat(detector.lifetimeCount(JutsuDriftSignal.SELECTOR_MISS)).isZero();
    }

    @Test
    void lifetimeCountersDoNotDecrementOnEviction() {
        JutsuDriftDetector detector = new JutsuDriftDetector(2, 1, 1, Clock.systemUTC());

        for (int i = 0; i < 5; i++) {
            detector.observe(JutsuDriftEvent.of(JutsuDriftSignal.NEW_CSS_CLASS, "p", "evt-" + i));
        }
        JutsuDriftSnapshot snap = detector.snapshot();

        assertThat(snap.eventsInWindow()).isEqualTo(2);
        assertThat(snap.lifetimeEvents()).isEqualTo(5);
        assertThat(detector.lifetimeCount(JutsuDriftSignal.NEW_CSS_CLASS)).isEqualTo(5);
    }

    @Test
    void snapshotIsAnImmutableCopy() {
        JutsuDriftDetector detector = new JutsuDriftDetector();
        detector.observe(JutsuDriftEvent.of(JutsuDriftSignal.NEW_CSS_CLASS, "p", "first"));

        JutsuDriftSnapshot snap = detector.snapshot();
        // Mutate detector state after capture.
        detector.observe(JutsuDriftEvent.of(JutsuDriftSignal.NEW_CSS_CLASS, "p", "second"));

        assertThat(snap.eventsInWindow())
                .as("snapshot should not reflect events observed after capture")
                .isEqualTo(1);
        assertThat(snap.recentEvents())
                .as("snapshot.recentEvents should be unmodifiable")
                .isUnmodifiable();
    }

    @Test
    void capturedAtUsesInjectedClock() {
        Instant fixed = Instant.parse("2026-05-04T03:30:00Z");
        Clock clock = Clock.fixed(fixed, ZoneOffset.UTC);
        JutsuDriftDetector detector = new JutsuDriftDetector(10, 5, 5, clock);

        JutsuDriftSnapshot snap = detector.snapshot();

        assertThat(snap.capturedAt()).isEqualTo(fixed);
    }

    @Test
    void observeNullEventIsANoOp() {
        JutsuDriftDetector detector = new JutsuDriftDetector();

        detector.observe(null);

        JutsuDriftSnapshot snap = detector.snapshot();
        assertThat(snap.lifetimeEvents()).isZero();
    }

    @Test
    void invalidConstructorArgsThrow() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new JutsuDriftDetector(0, 1, 1, Clock.systemUTC()));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new JutsuDriftDetector(10, 0, 1, Clock.systemUTC()));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new JutsuDriftDetector(10, 11, 1, Clock.systemUTC()));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new JutsuDriftDetector(10, 1, 0, Clock.systemUTC()));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new JutsuDriftDetector(10, 1, 1, null));
    }

    @Test
    void concurrentObserveDoesNotLoseEvents() throws Exception {
        // Stress test: 8 threads × 250 events each = 2000 events, larger than the default window
        // size, so we additionally check that lifetime counters are exact.
        JutsuDriftDetector detector = new JutsuDriftDetector();
        int threads = 8;
        int perThread = 250;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        List<Throwable> failures = new ArrayList<>();

        for (int t = 0; t < threads; t++) {
            final int threadId = t;
            pool.submit(
                    () -> {
                        try {
                            start.await();
                            for (int i = 0; i < perThread; i++) {
                                detector.observe(
                                        JutsuDriftEvent.of(
                                                JutsuDriftSignal.NEW_CSS_CLASS,
                                                "thread-" + threadId,
                                                "evt-" + i));
                            }
                        } catch (Throwable ex) {
                            synchronized (failures) {
                                failures.add(ex);
                            }
                        } finally {
                            done.countDown();
                        }
                    });
        }
        start.countDown();
        boolean finished = done.await(30, TimeUnit.SECONDS);
        pool.shutdownNow();

        assertThat(finished).as("worker threads should complete within 30s").isTrue();
        assertThat(failures).as("no thread should have raised").isEmpty();

        JutsuDriftSnapshot snap = detector.snapshot();
        assertThat(snap.lifetimeEvents()).isEqualTo(threads * perThread);
        assertThat(detector.lifetimeCount(JutsuDriftSignal.NEW_CSS_CLASS))
                .isEqualTo(threads * perThread);
        assertThat(snap.eventsInWindow())
                .isLessThanOrEqualTo(JutsuDriftDetector.DEFAULT_WINDOW_SIZE);
    }
}
