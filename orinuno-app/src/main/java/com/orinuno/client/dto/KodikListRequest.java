package com.orinuno.client.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KodikListRequest {

    private Integer limit;
    private String types;
    private String year;
    private String translationId;
    private String translationType;
    private String prioritizeTranslations;
    private String unprioritizeTranslations;
    private String prioritizeTranslationType;
    private String hasField;
    private String hasFieldAnd;
    private String blockTranslations;
    private Boolean camrip;
    private Boolean lgbt;
    private Boolean withSeasons;
    private Boolean withEpisodes;
    private Boolean withEpisodesData;
    private String season;
    private String episode;
    private Boolean withPageLinks;
    private String notBlockedIn;
    private String notBlockedForMe;
    private Boolean withMaterialData;
    private String countries;
    private String genres;
    private String animeGenres;
    private String dramaGenres;
    private String allGenres;
    private String duration;
    private String kinopoiskRating;
    private String imdbRating;
    private String shikimoriRating;
    private String mydramalistRating;
    private String actors;
    private String directors;
    private String producers;
    private String writers;
    private String composers;
    private String editors;
    private String designers;
    private String operators;
    private String ratingMpaa;
    private String minimalAge;
    private String animeKind;
    private String mydramalistTags;
    private String animeStatus;
    private String dramaStatus;
    private String allStatus;
    private String animeStudios;
    private String animeLicensedBy;
    private String sort;
    private String order;

    /**
     * Forward-pagination cursor returned by Kodik in the {@code next_page} field of a previous
     * {@code /list} response. When set, all other fields are ignored — Kodik replays the original
     * query.
     */
    private String nextPageUrl;

    /**
     * Backward-pagination cursor returned by Kodik in the {@code prev_page} field. Mirror of {@link
     * #nextPageUrl}; same semantics. Added in API-4 so callers can step back through paginated
     * lists without rebuilding state.
     */
    private String prevPageUrl;
}
