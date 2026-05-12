/*
 * EpisodeVideo — ADR 0021 Block B3-a (L2 in meter).
 *
 * Provider-specific decoded video URL keyed by (source_id, quality). One
 * row per (episode_source.id, quality) tuple. decode_method preserves the
 * DECODE-8 discriminator (REGEX / SNIFF / PROVIDER_API). ttl_seconds is
 * provider-specific (Aniboom CDN tokens ~6h; Sibnet direct URLs NULL).
 *
 * Mirror of orinuno-app's legacy com.orinuno.model.EpisodeVideo.
 */
package com.orinuno.meter.catalog.model;

import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

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
