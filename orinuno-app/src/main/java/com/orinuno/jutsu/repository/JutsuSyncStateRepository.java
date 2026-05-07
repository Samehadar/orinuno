package com.orinuno.jutsu.repository;

import com.orinuno.jutsu.model.JutsuSyncState;
import jakarta.annotation.Nullable;
import java.time.LocalDateTime;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * MyBatis mapper for {@code jutsu_sync_state}. Singleton row with id=1; rows are seeded by the
 * Liquibase changeset, but {@link #seedIfMissing()} guards the ad-hoc tests that truncate before
 * each run.
 */
@Mapper
public interface JutsuSyncStateRepository {

    Optional<JutsuSyncState> load();

    /** Update the full-crawl cursor. */
    int updateFullCrawlCursor(@Param("at") LocalDateTime at);

    int updateNoticeCursor(@Param("cursor") @Nullable Integer cursor);

    /**
     * Atomic "acquire-or-recover" update. Sets {@code notice_walk_in_progress=TRUE} when the row is
     * currently free OR when the previous holder's {@code updated_at} is older than {@code
     * staleBefore} (the lock TTL — handles a crashed worker). Returns 1 when the lock was acquired,
     * 0 otherwise.
     */
    int tryLockNoticeWalk(@Param("staleBefore") LocalDateTime staleBefore);

    int releaseNoticeWalk();

    int seedIfMissing();
}
