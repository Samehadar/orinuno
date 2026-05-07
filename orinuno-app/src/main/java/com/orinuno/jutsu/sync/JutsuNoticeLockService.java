package com.orinuno.jutsu.sync;

import com.orinuno.configuration.JutsuSyncProperties;
import com.orinuno.jutsu.repository.JutsuSyncStateRepository;
import jakarta.annotation.Nullable;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Dedicated transactional facade for the {@code jutsu_sync_state} singleton row. Lives in its own
 * bean so Spring's {@code @Transactional} proxy intercepts the call from {@link
 * JutsuCatalogSyncService} (avoids the self-invocation pitfall — same lesson as {@link
 * com.orinuno.service.requestlog.ParseRequestQueueService}).
 *
 * <p>Lock semantics:
 *
 * <ul>
 *   <li>{@link #tryAcquire()} — single SQL UPDATE that flips {@code notice_walk_in_progress=TRUE}
 *       only when the row is free OR the previous holder's {@code updated_at} is older than {@link
 *       JutsuSyncProperties#effectiveNoticeLockTtlMinutes()}. This handles a crash that left the
 *       flag stuck.
 *   <li>{@link #release()} — REQUIRES_NEW so a failure inside the calling transaction can't prevent
 *       the row from being released.
 *   <li>{@link #currentCursor()} / {@link #saveCursor(Integer)} — straight reads/writes of {@code
 *       last_notice_cursor}, used by the sync service.
 * </ul>
 */
@Slf4j
@Service
public class JutsuNoticeLockService {

    private final JutsuSyncStateRepository repository;
    private final JutsuSyncProperties properties;
    private final Clock clock;

    public JutsuNoticeLockService(
            JutsuSyncStateRepository repository, JutsuSyncProperties properties, Clock clock) {
        this.repository = repository;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional
    public boolean tryAcquire() {
        repository.seedIfMissing();
        LocalDateTime staleBefore = now().minusMinutes(properties.effectiveNoticeLockTtlMinutes());
        int updated = repository.tryLockNoticeWalk(staleBefore);
        if (updated > 0) {
            log.debug("🔒 jut.su notice walk lock acquired");
            return true;
        }
        log.debug("⏭️ jut.su notice walk lock busy (held by another instance)");
        return false;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void release() {
        try {
            repository.releaseNoticeWalk();
        } catch (RuntimeException ex) {
            log.warn("⚠️ Failed to release jut.su notice walk lock", ex);
        }
    }

    @Transactional(readOnly = true)
    public Optional<Integer> currentCursor() {
        return repository.load().map(s -> s.getLastNoticeCursor());
    }

    @Transactional
    public void saveCursor(@Nullable Integer cursor) {
        repository.updateNoticeCursor(cursor);
    }

    @Transactional
    public void markFullCrawl() {
        repository.updateFullCrawlCursor(now());
    }

    @Transactional(readOnly = true)
    public Optional<LocalDateTime> lastFullCrawlAt() {
        return repository.load().map(s -> s.getLastFullCrawlAt());
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneId.systemDefault());
    }
}
