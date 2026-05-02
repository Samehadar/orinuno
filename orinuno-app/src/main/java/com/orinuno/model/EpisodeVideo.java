package com.orinuno.model;

import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * PLAYER-1 (ADR 0005) — provider-specific decoded video URL. One row per (source_id, quality). The
 * {@link #decodeMethod} field preserves the DECODE-8 discriminator (REGEX / SNIFF / PROVIDER_API).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EpisodeVideo {

    private Long id;
    private Long sourceId;
    private String quality;
    private String videoUrl;
    private String videoFormat;
    private LocalDateTime decodedAt;
    private String decodeMethod;
    private Integer decodeFailedCount;
    private String decodeLastError;
    private Integer ttlSeconds;
}
