package com.orinuno.model;

import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One row of {@code kodik_decoder_path_cache} (DECODE-2). Persists the per-netloc video-info POST
 * path that {@link com.orinuno.service.KodikVideoDecoderService} discovers from the player JS, so a
 * JVM restart doesn't force every netloc through the brute-force discovery path again.
 *
 * <p>Keyed by {@link #netloc} (lower-cased host, no port). {@link #hitCount} is incremented on
 * every successful decode that hit this cache row.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KodikDecoderPathCacheEntry {

    private String netloc;
    private String videoInfoPath;
    private Long hitCount;
    private LocalDateTime firstSeenAt;
    private LocalDateTime lastSeenAt;
}
