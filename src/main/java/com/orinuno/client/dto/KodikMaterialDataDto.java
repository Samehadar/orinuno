package com.orinuno.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Single source of truth for the set of JSON fields Kodik is known to emit under {@code
 * results[*].material_data}. The record is used in two ways:
 *
 * <ul>
 *   <li>{@link com.orinuno.drift.DtoFieldExtractor} introspects its components to produce the
 *       canonical set of known field names (snake_case via Jackson fallback).
 *   <li>Stability tests ({@code KodikApiStabilityTest}) and the runtime drift sampler in {@code
 *       KodikResponseMapper} both use this set — keep them in sync by adding new components here
 *       when a Kodik change is observed.
 * </ul>
 *
 * <p>Not currently used for deserialization (material_data is left as {@code Map<String, Object>}
 * end-to-end). If typed access is added later, {@code @JsonIgnoreProperties(ignoreUnknown = true)}
 * already tolerates Kodik drift.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record KodikMaterialDataDto(
        String title,
        String animeTitle,
        String titleEn,
        Object otherTitles,
        Object otherTitlesEn,
        Object otherTitlesJp,
        String animeLicenseName,
        Object animeLicensedBy,
        String animeKind,
        String allStatus,
        String animeStatus,
        String dramaStatus,
        Integer year,
        String description,
        String posterUrl,
        Object screenshots,
        Integer duration,
        Object countries,
        Object allGenres,
        Object genres,
        Object animeGenres,
        Object dramaGenres,
        Object animeStudios,
        String rating,
        Double kinopoiskRating,
        Integer kinopoiskVotes,
        Double imdbRating,
        Integer imdbVotes,
        Double shikimoriRating,
        Integer shikimoriVotes,
        Double mydramalistRating,
        Integer mydramalistVotes,
        String premiereWorld,
        String premiereRu,
        String premiereCountry,
        String airedAt,
        String releasedAt,
        String nextEpisodeAt,
        Integer episodesTotal,
        Integer episodesAired,
        Object actors,
        Object directors,
        Object producers,
        Object writers,
        Object composers,
        Object editors,
        Object designers,
        Object operators,
        String ratingMpaa,
        Integer minimalAge,
        String animeDescription,
        String posterUrlOriginal,
        Object mydramalistTags,
        Object blockedCountries,
        Object blockedSeasons,
        String animePosterUrl,
        String dramaPosterUrl,
        String tagline) {}
