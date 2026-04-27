package com.orinuno.model.dto;

import com.orinuno.client.dto.calendar.KodikCalendarEntryDto;
import java.time.Instant;
import java.util.List;

/**
 * Envelope for {@code GET /api/v1/calendar}. Wraps the upstream {@code calendar.json} dump with
 * fetch metadata so consumers can implement client-side caching and freshness checks.
 *
 * <p>{@code etag} is the upstream {@code ETag} header; orinuno uses it internally for conditional
 * GET against {@code dumps.kodikres.com}. The header is exposed unchanged to clients so a
 * downstream proxy can cache by the same token. {@code fetchedAt} marks when this response was
 * pulled from upstream — for cache hits it points at the timestamp of the originating fetch, not
 * the cache lookup.
 *
 * <p>{@code entries} are the (optionally filtered, optionally enriched) records. {@code total}
 * counts entries in this response — not in the upstream dump.
 */
public record CalendarResponse(
        Instant fetchedAt, String etag, Integer total, List<EnrichedCalendarEntryDto> entries) {

    public record EnrichedCalendarEntryDto(KodikCalendarEntryDto entry, Long orinunoContentId) {}
}
