package com.orinuno.jutsu.model;

import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Singleton sync-state row for {@code JutsuCatalogSyncService} (ARCH-0016 P1a). Backs {@code
 * jutsu_sync_state} (see migration {@code 20260508010200_create_jutsu_sync_state.sql} for the shape
 * and the CHECK-constraint enforcing the singleton). Always {@code id = 1}.
 *
 * <p>Persisted between worker ticks so:
 *
 * <ul>
 *   <li>a crash mid-crawl resumes from the last completed page rather than restarting at page 1;
 *   <li>the notice-feed walker doesn't re-process a backlog of older notices on every tick;
 *   <li>the {@code orinuno_jutsu_titles_total} Prometheus gauge can read a denormalised counter
 *       without doing {@code SELECT COUNT(*)} on the (potentially large) L1 table.
 * </ul>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class JutsuSyncState {

    public static final int SINGLETON_ID = 1;

    private int id;
    private LocalDateTime fullCrawlStartedAt;
    private LocalDateTime fullCrawlCompletedAt;
    private Integer fullCrawlLastPage;
    private Integer fullCrawlTotalPages;
    private Integer noticeCursor;
    private LocalDateTime noticeCursorUpdatedAt;
    private LocalDateTime noticeLastWalkedAt;
    private long totalTitlesSynced;
    private String lastError;
    private LocalDateTime lastErrorAt;
    private LocalDateTime updatedAt;

    /** Convenience: a fresh singleton with everything blank, ready for first-run insertion. */
    public static JutsuSyncState empty(LocalDateTime now) {
        return JutsuSyncState.builder()
                .id(SINGLETON_ID)
                .totalTitlesSynced(0L)
                .updatedAt(now)
                .build();
    }
}
