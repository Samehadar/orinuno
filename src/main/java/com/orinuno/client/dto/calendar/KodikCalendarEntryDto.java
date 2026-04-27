package com.orinuno.client.dto.calendar;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;
import java.time.LocalDate;

/**
 * One row of the public {@code https://dumps.kodikres.com/calendar.json} dump (IDEA-AP-5). Each
 * entry describes one anime tracked by Kodik with its next-episode schedule, current airing state,
 * and Shikimori-sourced metadata.
 *
 * <p>Upstream snake_case keys are accepted via {@link JsonAlias} on read; serialization back to API
 * clients uses the camelCase record component names (matching the rest of orinuno's public DTOs).
 * {@code airedOn} / {@code releasedOn} are dates without time; {@code nextEpisodeAt} carries an
 * instant (UTC).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record KodikCalendarEntryDto(
        @JsonAlias("next_episode") Integer nextEpisode,
        @JsonAlias("next_episode_at") Instant nextEpisodeAt,
        Integer duration,
        KodikCalendarAnimeDto anime,
        String kind,
        Double score,
        String status,
        Integer episodes,
        @JsonAlias("episodes_aired") Integer episodesAired,
        @JsonAlias("aired_on") LocalDate airedOn,
        @JsonAlias("released_on") LocalDate releasedOn) {}
