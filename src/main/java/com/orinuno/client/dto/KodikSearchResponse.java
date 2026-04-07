package com.orinuno.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class KodikSearchResponse {

    private String time;
    private Integer total;

    @JsonProperty("prev_page")
    private String prevPage;

    @JsonProperty("next_page")
    private String nextPage;

    private List<Result> results;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Result {

        private String id;
        private String type;
        private String link;
        private String title;

        @JsonProperty("title_orig")
        private String titleOrig;

        @JsonProperty("other_title")
        private String otherTitle;

        private Translation translation;
        private Integer year;

        @JsonProperty("last_season")
        private Integer lastSeason;

        @JsonProperty("last_episode")
        private Integer lastEpisode;

        @JsonProperty("episodes_count")
        private Integer episodesCount;

        @JsonProperty("kinopoisk_id")
        private String kinopoiskId;

        @JsonProperty("imdb_id")
        private String imdbId;

        @JsonProperty("shikimori_id")
        private String shikimoriId;

        @JsonProperty("worldart_link")
        private String worldartLink;

        private String quality;
        private Boolean camrip;
        private Boolean lgbt;

        @JsonProperty("created_at")
        private String createdAt;

        @JsonProperty("updated_at")
        private String updatedAt;

        @JsonProperty("blocked_countries")
        private List<String> blockedCountries;

        @JsonProperty("blocked_seasons")
        private Map<String, Object> blockedSeasons;

        private List<String> screenshots;

        private Map<String, Season> seasons;

        @JsonProperty("material_data")
        private Map<String, Object> materialData;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Translation {
        private Integer id;
        private String title;
        private String type;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Season {
        private String link;
        private String title;
        private Map<String, String> episodes;
    }
}
