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

    /**
     * Custom Lombok builder extension (API-7) — adds fluent type-shortcut methods that compile to
     * the correct comma-separated {@code types} param without callers having to remember the
     * literal API strings.
     *
     * <p>Lombok still generates setters for every other field; only methods explicitly declared
     * here override Lombok's defaults.
     */
    public static class KodikSearchRequestBuilder {

        /**
         * Backward-compatible String setter — Lombok skips its own {@code types(String)} once any
         * {@code types(...)} method exists in this inner class, so we restore it explicitly.
         */
        public KodikSearchRequestBuilder types(String types) {
            this.types = types;
            return this;
        }

        /** Filter by an explicit set of types. */
        public KodikSearchRequestBuilder types(KodikType... types) {
            this.types = KodikType.csv(types);
            return this;
        }

        /** Filter by both anime kinds (serial + movie). */
        public KodikSearchRequestBuilder anime() {
            this.types = KodikType.csv(KodikType.ANIME_KINDS);
            return this;
        }

        /** Filter by all serial kinds (anime + foreign + russian). */
        public KodikSearchRequestBuilder serials() {
            this.types = KodikType.csv(KodikType.SERIAL_KINDS);
            return this;
        }

        /** Filter by all non-anime feature-length kinds + anime movies. */
        public KodikSearchRequestBuilder movies() {
            this.types = KodikType.csv(KodikType.MOVIE_KINDS);
            return this;
        }

        /** Filter by all cartoon kinds (russian + foreign + soviet). */
        public KodikSearchRequestBuilder cartoons() {
            this.types = KodikType.csv(KodikType.CARTOON_KINDS);
            return this;
        }
    }
}
