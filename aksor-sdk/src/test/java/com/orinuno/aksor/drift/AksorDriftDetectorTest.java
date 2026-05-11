package com.orinuno.aksor.drift;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class AksorDriftDetectorTest {

    @Test
    void enabledDetectorCountsAndKeepsRecent() {
        AksorDriftDetector d = new AksorDriftDetector();
        assertThat(d.isEnabled()).isTrue();
        d.record(AksorDriftSignal.YUMMY_PAGE_NO_ANIME_ID, Map.of("pageUrl", "https://x"));
        d.record(AksorDriftSignal.YUMMY_PAGE_NO_ANIME_ID, Map.of("pageUrl", "https://y"));
        d.record(AksorDriftSignal.AKSOR_QUALITIES_MISSING, Map.of("hash", "abc"));

        AksorDriftSnapshot snap = d.snapshot();
        assertThat(snap.count(AksorDriftSignal.YUMMY_PAGE_NO_ANIME_ID)).isEqualTo(2);
        assertThat(snap.count(AksorDriftSignal.AKSOR_QUALITIES_MISSING)).isEqualTo(1);
        assertThat(snap.count(AksorDriftSignal.AKSOR_QUALITIES_ALL_NULL)).isZero();
        assertThat(snap.total()).isEqualTo(3);
        assertThat(snap.isClean()).isFalse();
        assertThat(snap.recent()).hasSize(3);
        assertThat(snap.recent().get(0).context()).containsEntry("pageUrl", "https://x");
        assertThat(snap.recent().get(2).signal())
                .isEqualTo(AksorDriftSignal.AKSOR_QUALITIES_MISSING);
    }

    @Test
    void disabledDetectorSwallowsEverything() {
        AksorDriftDetector d = AksorDriftDetector.disabled();
        assertThat(d.isEnabled()).isFalse();
        d.record(AksorDriftSignal.YUMMY_PAGE_NO_ANIME_ID);
        d.record(AksorDriftSignal.AKSOR_QUALITIES_MISSING);

        AksorDriftSnapshot snap = d.snapshot();
        assertThat(snap.total()).isZero();
        assertThat(snap.isClean()).isTrue();
        assertThat(snap.recent()).isEmpty();
    }

    @Test
    void ringBufferEvictsOldestAndIncrementsDropped() {
        AksorDriftDetector d = new AksorDriftDetector(3);
        for (int i = 0; i < 10; i++) {
            d.record(AksorDriftSignal.YUMMY_EPISODE_NO_HASH, Map.of("i", String.valueOf(i)));
        }
        AksorDriftSnapshot snap = d.snapshot();
        assertThat(snap.count(AksorDriftSignal.YUMMY_EPISODE_NO_HASH)).isEqualTo(10);
        assertThat(snap.recent()).hasSize(3);
        // Last three retained: i=7, 8, 9.
        assertThat(snap.recent().get(0).context()).containsEntry("i", "7");
        assertThat(snap.recent().get(2).context()).containsEntry("i", "9");
        assertThat(d.droppedFromRing()).isEqualTo(7);
    }

    @Test
    void resetClearsCountsAndRecent() {
        AksorDriftDetector d = new AksorDriftDetector();
        d.record(AksorDriftSignal.YUMMY_PAGE_NO_ANIME_ID);
        d.record(AksorDriftSignal.AKSOR_QUALITIES_ALL_NULL);

        d.reset();
        assertThat(d.snapshot().total()).isZero();
        assertThat(d.snapshot().recent()).isEmpty();
        assertThat(d.droppedFromRing()).isZero();
    }

    @Test
    void recordIsThreadSafe() throws Exception {
        AksorDriftDetector d = new AksorDriftDetector(1024);
        int workers = 8;
        int perWorker = 500;
        ExecutorService pool = Executors.newFixedThreadPool(workers);
        try {
            for (int w = 0; w < workers; w++) {
                pool.submit(
                        () -> {
                            for (int i = 0; i < perWorker; i++) {
                                d.record(AksorDriftSignal.YUMMY_EPISODE_NO_HASH);
                            }
                        });
            }
        } finally {
            pool.shutdown();
            assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(d.snapshot().count(AksorDriftSignal.YUMMY_EPISODE_NO_HASH))
                .isEqualTo((long) workers * perWorker);
    }

    @Test
    void nullSignalIgnored() {
        AksorDriftDetector d = new AksorDriftDetector();
        d.record(null);
        assertThat(d.snapshot().total()).isZero();
    }

    @Test
    void zeroRingSizeRejected() {
        assertThatThrownBy(() -> new AksorDriftDetector(0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
