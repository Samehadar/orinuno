/*
 * EpisodeVariantDto — ADR 0021 §C1.1.
 *
 * Wire format for GET /api/v1/content/{id}/variants in source-kodik.
 * Identical to the legacy orinuno-app EpisodeVariantDto.
 */
package com.orinuno.source.kodik.model.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EpisodeVariantDto {

    private Long id;
    private Long contentId;
    private Integer seasonNumber;
    private Integer episodeNumber;
    private Integer translationId;
    private String translationTitle;
    private String translationType;
    private String quality;
    private String kodikLink;
    private String mp4Link;
    private String localFilepath;
}
