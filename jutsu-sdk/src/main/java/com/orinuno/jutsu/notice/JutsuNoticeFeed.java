package com.orinuno.jutsu.notice;

import java.util.List;
import java.util.Optional;

/**
 * One page of the jut.su notice feed — the response to {@code POST /engine/ajax/site_notice.php}
 * with {@code action=show&notice_id={cursor}}.
 *
 * <p>jut.su returns up to {@link #PAGE_SIZE} entries per cursor, each one ID lower than the
 * previous (notice IDs are contiguous). When {@code cursor} drops below the oldest preserved notice
 * the response body is empty (a 0-byte body) and {@link #entries()} is empty.
 *
 * @param requestedCursor the {@code notice_id} we asked for; the feed contains this entry plus up
 *     to 49 older ones
 * @param entries notice entries newest-first; never null but may be empty at the history bound
 */
public record JutsuNoticeFeed(int requestedCursor, List<JutsuNoticeEntry> entries) {

    /** Page size jut.su currently uses on the live site. */
    public static final int PAGE_SIZE = 50;

    public JutsuNoticeFeed {
        if (requestedCursor < 0) {
            throw new IllegalArgumentException("requestedCursor must be ≥ 0: " + requestedCursor);
        }
        entries = entries == null ? List.of() : List.copyOf(entries);
    }

    /** {@code true} when this feed has at least one entry, {@code false} at the history bound. */
    public boolean hasEntries() {
        return !entries.isEmpty();
    }

    /**
     * Cursor to request the next (older) page. Returns empty when {@link #hasEntries()} is false
     * (we hit the history bound) or the cursor would underflow.
     *
     * <p>jut.su's notice IDs are contiguous, so {@code prevCursor = requestedCursor -
     * entries.size()}. Note that {@code requestedCursor - entries.size() + 1} is the OLDEST id in
     * this batch; the next batch starts one below it.
     */
    public Optional<Integer> nextCursor() {
        if (entries.isEmpty()) return Optional.empty();
        int next = requestedCursor - entries.size();
        if (next < 0) return Optional.empty();
        return Optional.of(next);
    }
}
