package com.orinuno.client.dto.calendar;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Shikimori-hosted poster bundle for one Kodik calendar entry. Sizes mirror Shikimori's image
 * pipeline ({@code original}, {@code preview}, {@code x96}, {@code x48}, {@code x24}). All fields
 * are nullable because the upstream dump occasionally omits intermediate sizes.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record KodikCalendarImageDto(
        String original, String preview, String x96, String x48, String x24) {}
