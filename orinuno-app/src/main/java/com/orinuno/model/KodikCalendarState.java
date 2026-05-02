package com.orinuno.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Per-anime snapshot of the last seen Kodik calendar entry. Backs the {@code kodik_calendar_state}
 * table created in CAL-6. One row per Shikimori id; updated on every successful watch cycle.
 *
 * <p>{@link #firstSeenAt} is set on insert and never updated; {@link #lastSeenAt} bumps on every
 * watch cycle whether or not the row's data changed (so it doubles as a "is this anime still in the
 * upstream calendar?" liveness signal).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KodikCalendarState {

    private String shikimoriId;
    private Integer nextEpisode;
    private LocalDateTime nextEpisodeAt;
    private Integer episodesAired;
    private Integer episodesTotal;
    private String status;
    private BigDecimal score;
    private String kind;
    private LocalDate airedOn;
    private LocalDate releasedOn;
    private LocalDateTime firstSeenAt;
    private LocalDateTime lastSeenAt;
}
