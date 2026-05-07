package com.orinuno.jutsu.model;

import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * L1 per-source cache row for one jut.su episode listing (ARCH-0016 P1a). Backs {@code
 * jutsu_episode}. Composite key {@code (slug, season, episode)} mirrors how jut.su URLs are
 * structured ({@code /{slug}/season-N/episode-M.html} or {@code /{slug}/episode-M.html} for
 * single-season anime; the SDK collapses the latter into {@code season=1}).
 *
 * <p>{@code paywalled} is a tri-state flag: {@code TRUE} means the catalog observed a Jutsu+ gate,
 * {@code FALSE} means anonymous decode worked at last fetch, {@code NULL} means we have not yet
 * probed (the catalog crawl alone doesn't tell us — it just lists episodes).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class JutsuEpisode {

    private String slug;
    private int season;
    private int episode;
    private String label;
    private String relativeUrl;
    private Boolean paywalled;
    private LocalDateTime discoveredAt;
    private LocalDateTime lastSeenAt;
}
