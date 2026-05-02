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
public class KodikEpisodeVariant {

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
    private String decodeMethod;
    private LocalDateTime mp4LinkDecodedAt;
    private String localFilepath;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
