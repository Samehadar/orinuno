package com.orinuno.jutsu.model;

import jakarta.annotation.Nullable;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * MyBatis row for {@code jutsu_episode} (ADR 0016 P1a). The {@link #videoQualities} column is a
 * JSON blob ({@code {"480":"…","720":"…"}}) serialised as a String for the upsert path.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class JutsuEpisode {
    private String titleSlug;
    private int season;
    private int episode;
    @Nullable private String embedUrl;
    @Nullable private String videoQualities;
    @Nullable private LocalDateTime lastSyncedAt;
}
