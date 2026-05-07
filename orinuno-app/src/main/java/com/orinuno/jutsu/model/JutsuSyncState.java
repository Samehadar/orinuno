package com.orinuno.jutsu.model;

import jakarta.annotation.Nullable;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** MyBatis row for {@code jutsu_sync_state}. Singleton — id is always {@code 1}. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class JutsuSyncState {
    private int id;
    @Nullable private LocalDateTime lastFullCrawlAt;
    @Nullable private Integer lastNoticeCursor;
    private boolean noticeWalkInProgress;
    @Nullable private LocalDateTime updatedAt;
}
