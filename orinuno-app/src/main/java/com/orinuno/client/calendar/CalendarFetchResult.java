package com.orinuno.client.calendar;

import com.orinuno.client.dto.calendar.KodikCalendarEntryDto;
import java.time.Instant;
import java.util.List;

/**
 * Output of one fetch from {@code dumps.kodikres.com/calendar.json}. {@code fetchedAt} is the
 * instant the upstream returned 200 (not 304); {@code etag} / {@code lastModified} mirror the
 * upstream headers and feed the next conditional GET. {@code entries} is the parsed payload.
 */
public record CalendarFetchResult(
        Instant fetchedAt, String etag, String lastModified, List<KodikCalendarEntryDto> entries) {}
