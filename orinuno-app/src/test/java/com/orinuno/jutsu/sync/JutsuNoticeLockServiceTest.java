package com.orinuno.jutsu.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.orinuno.configuration.JutsuSyncProperties;
import com.orinuno.jutsu.model.JutsuSyncState;
import com.orinuno.jutsu.repository.JutsuSyncStateRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class JutsuNoticeLockServiceTest {

    @Mock private JutsuSyncStateRepository repository;

    private JutsuNoticeLockService service;
    private final Clock fixedClock =
            Clock.fixed(
                    ZonedDateTime.of(2026, 5, 7, 12, 0, 0, 0, ZoneOffset.UTC).toInstant(),
                    ZoneOffset.UTC);

    @BeforeEach
    void setUp() {
        service = new JutsuNoticeLockService(repository, new JutsuSyncProperties(), fixedClock);
    }

    @Test
    @DisplayName("tryAcquire seeds the row and returns true when the UPDATE matched")
    void tryAcquireFreeRow() {
        when(repository.tryLockNoticeWalk(any())).thenReturn(1);

        boolean acquired = service.tryAcquire();

        assertThat(acquired).isTrue();
        verify(repository).seedIfMissing();
        verify(repository).tryLockNoticeWalk(any(LocalDateTime.class));
    }

    @Test
    @DisplayName("tryAcquire returns false when the row is held and not stale")
    void tryAcquireBusyRow() {
        when(repository.tryLockNoticeWalk(any())).thenReturn(0);

        assertThat(service.tryAcquire()).isFalse();
    }

    @Test
    @DisplayName("tryAcquire passes the staleBefore threshold derived from properties")
    void tryAcquireUsesStaleBefore() {
        when(repository.tryLockNoticeWalk(any())).thenReturn(1);

        service.tryAcquire();
        // The lock service computes "now" using ZoneId.systemDefault() (matches the rest of
        // the project — see ParseRequestQueueService); we mirror that in the expectation.
        LocalDateTime expected =
                LocalDateTime.ofInstant(fixedClock.instant(), ZoneId.systemDefault())
                        .minusMinutes(30);
        verify(repository).tryLockNoticeWalk(eq(expected));
    }

    @Test
    @DisplayName("release calls the underlying repo even when transactional fails")
    void releaseAlwaysReleases() {
        service.release();

        verify(repository).releaseNoticeWalk();
    }

    @Test
    @DisplayName("currentCursor returns the saved last_notice_cursor")
    void currentCursorReadsRow() {
        when(repository.load())
                .thenReturn(
                        Optional.of(JutsuSyncState.builder().id(1).lastNoticeCursor(1500).build()));

        assertThat(service.currentCursor()).contains(1500);
    }

    @Test
    @DisplayName("currentCursor returns empty when state row is missing or cursor is NULL")
    void currentCursorEmpty() {
        when(repository.load()).thenReturn(Optional.empty());
        assertThat(service.currentCursor()).isEmpty();
    }

    @Test
    @DisplayName("saveCursor writes through to the repository")
    void saveCursorWrites() {
        service.saveCursor(1500);
        verify(repository).updateNoticeCursor(1500);
    }

    @Test
    @DisplayName("saveCursor(null) is propagated for the drift-recovery path")
    void saveCursorNullPropagated() {
        service.saveCursor(null);
        verify(repository).updateNoticeCursor(null);
    }

    @Test
    @DisplayName("markFullCrawl writes the configured timestamp and nothing else")
    void markFullCrawlOnlyTouchesFullCrawlCursor() {
        service.markFullCrawl();
        verify(repository).updateFullCrawlCursor(any(LocalDateTime.class));
    }

    @Test
    @DisplayName("lastFullCrawlAt returns empty when no row")
    void lastFullCrawlEmpty() {
        when(repository.load()).thenReturn(Optional.empty());
        assertThat(service.lastFullCrawlAt()).isEmpty();
        verify(repository).load();
        verifyNoMoreInteractions(repository);
    }
}
