package com.orinuno.service.calendar;

/**
 * Query parameters for {@code GET /api/v1/calendar}. All fields are optional — {@code null} means
 * "do not filter on this dimension". Records are immutable so the controller can pass instances
 * straight to the service without defensive copies.
 *
 * <p>{@code status} matches Shikimori's {@code status} enum (e.g. {@code ongoing}, {@code anons},
 * {@code released}, {@code latest}). {@code kind} matches Shikimori's {@code kind} (e.g. {@code
 * tv}, {@code movie}, {@code ova}, {@code ona}). {@code minScore} is inclusive. {@code limit} caps
 * the entry list returned to the caller — used to keep responses small for casual clients; {@code
 * null} returns everything.
 */
public record CalendarFilter(String status, String kind, Double minScore, Integer limit) {

    public static CalendarFilter none() {
        return new CalendarFilter(null, null, null, null);
    }
}
