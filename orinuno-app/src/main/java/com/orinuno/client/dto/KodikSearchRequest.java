package com.orinuno.client.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KodikSearchRequest {

    private String title;
    private String titleOrig;
    private Boolean strict;
    private Boolean fullMatch;
    private String id;
    private String playerLink;
    private String kinopoiskId;
    private String imdbId;
    private String mdlId;
    private String worldartAnimationId;
    private String worldartCinemaId;
    private String worldartLink;
    private String shikimoriId;
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
}
