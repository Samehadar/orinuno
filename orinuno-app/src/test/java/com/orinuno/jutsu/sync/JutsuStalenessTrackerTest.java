package com.orinuno.jutsu.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.orinuno.configuration.JutsuSyncProperties;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class JutsuStalenessTrackerTest {

    @Mock private JutsuNoticeLockService lockService;

    private final Clock fixedClock =
            Clock.fixed(
                    ZonedDateTime.of(2026, 5, 7, 12, 0, 0, 0, ZoneOffset.UTC).toInstant(),
                    ZoneOffset.UTC);

    @Test
    @DisplayName(
            "staleSeconds defaults to the configured full-crawl interval when no crawl has run")
    void noCrawlYet() {
        when(lockService.lastFullCrawlAt()).thenReturn(Optional.empty());
        JutsuStalenessTracker tracker =
                new JutsuStalenessTracker(lockService, new JutsuSyncProperties(), fixedClock);
        long expected = 48L * 3600L; // default full-crawl interval = 48h
        assertThat(tracker.staleSeconds()).isEqualTo(expected);
    }

    @Test
    @DisplayName("staleSeconds reports the wall-clock distance from the last crawl")
    void afterCrawl() {
        // Tracker compares against ZoneId.systemDefault() — mirror that here so the test
        // is timezone-independent.
        LocalDateTime crawl =
                LocalDateTime.ofInstant(fixedClock.instant(), ZoneId.systemDefault())
                        .minusMinutes(5);
        when(lockService.lastFullCrawlAt()).thenReturn(Optional.of(crawl));
        JutsuStalenessTracker tracker =
                new JutsuStalenessTracker(lockService, new JutsuSyncProperties(), fixedClock);
        assertThat(tracker.staleSeconds()).isEqualTo(300L); // 5 min
    }

    @Test
    @DisplayName("Repeated reads inside the cache window only hit the lock service once")
    void cachesRepeatedReads() {
        when(lockService.lastFullCrawlAt()).thenReturn(Optional.empty());
        JutsuStalenessTracker tracker =
                new JutsuStalenessTracker(lockService, new JutsuSyncProperties(), fixedClock);
        tracker.staleSeconds();
        tracker.staleSeconds();
        tracker.staleSeconds();
        verify(lockService, times(1)).lastFullCrawlAt();
    }

    @Test
    @DisplayName("invalidate() forces a fresh read on the next call")
    void invalidateForcesRead() {
        when(lockService.lastFullCrawlAt()).thenReturn(Optional.empty());
        JutsuStalenessTracker tracker =
                new JutsuStalenessTracker(lockService, new JutsuSyncProperties(), fixedClock);
        tracker.staleSeconds();
        tracker.invalidate();
        tracker.staleSeconds();
        verify(lockService, times(2)).lastFullCrawlAt();
    }
}
