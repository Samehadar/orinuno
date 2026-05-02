package com.orinuno.service.calendar;

/**
 * Discriminator for {@link com.orinuno.model.KodikCalendarOutboxEvent#getChangeType()} (CAL-6).
 *
 * <p>Stored as a string in the DB so unknown future change types from a newer orinuno can be read
 * by an older consumer without a database migration.
 */
public enum CalendarChangeType {
    NEW_ANIME,
    NEXT_EPISODE_ADVANCED,
    NEXT_EPISODE_RESCHEDULED,
    EPISODES_AIRED_INCREASED,
    STATUS_CHANGED,
    SCORE_CHANGED,
    RELEASED_ON_SET
}
