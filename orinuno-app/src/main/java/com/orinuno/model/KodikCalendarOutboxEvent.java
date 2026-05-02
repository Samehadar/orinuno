package com.orinuno.model;

import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One detected delta in {@code kodik_calendar_outbox} (CAL-6). Append-only — once written, only
 * {@link #consumerId} / {@link #consumedAt} ever change.
 *
 * <p>{@link #changeType} is the discriminator (NEW_ANIME, NEXT_EPISODE_ADVANCED, …). {@link
 * #oldValue} / {@link #newValue} carry a string-encoded representation of the affected field's
 * before/after — string so heterogeneous types (int / Instant / BigDecimal / status string) all fit
 * a single column without schema gymnastics.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KodikCalendarOutboxEvent {

    private Long id;
    private String shikimoriId;
    private String changeType;
    private String oldValue;
    private String newValue;
    private LocalDateTime detectedAt;
    private String consumerId;
    private LocalDateTime consumedAt;
}
