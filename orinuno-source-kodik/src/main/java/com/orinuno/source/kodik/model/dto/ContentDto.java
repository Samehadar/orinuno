/*
 * ContentDto — ADR 0021 §C1.1.
 *
 * Wire format for GET /api/v1/content/* in source-kodik. Identical field set
 * to the legacy orinuno-app ContentDto so the demo UI sees no diff during
 * the reverse-proxy cutover (ADR 0021 §C1.2). screenshots / materialData
 * are pre-parsed JSON — see ContentDtoMapper.
 */
package com.orinuno.source.kodik.model.dto;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

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
