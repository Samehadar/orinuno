package com.orinuno.client.dto.calendar;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Shikimori-side projection of a Kodik calendar entry. {@code id} is the Shikimori anime id stored
 * as a string by upstream — we keep it as-is to match the {@code kodik_content.shikimori_id}
 * column. {@code russian} / {@code name} are the localized and original titles respectively.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record KodikCalendarAnimeDto(
        String id, String name, String russian, KodikCalendarImageDto image) {}
