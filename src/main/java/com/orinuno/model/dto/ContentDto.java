package com.orinuno.model.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ContentDto {

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
    private List<String> screenshots;
    private Map<String, Object> materialData;
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
