package com.orinuno.model;

import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KodikContent {

    private Long id;
    private String kodikId;
    private String type;
    private String title;
    private String titleOrig;
    private String otherTitle;
    private Integer year;
    private String kinopoiskId;
    private String imdbId;
    private String shikimoriId;
    private String worldartLink;
    private String screenshots;
    private String materialData;
    private Double kinopoiskRating;
    private Double imdbRating;
    private Double shikimoriRating;
    private String genres;
    private String blockedCountries;
    private Boolean camrip;
    private Boolean lgbt;
    private Integer lastSeason;
    private Integer lastEpisode;
    private Integer episodesCount;
    private String quality;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
