package com.orinuno.model.dto;

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
