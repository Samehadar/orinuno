package com.orinuno.source.jutsu.model;

import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * L1 per-source cache row for one jut.su full-length movie listing (ARCH-0016 P1a). Backs {@code
 * jutsu_film}. Composite key {@code (slug, filmIndex)} mirrors how jut.su URLs are structured
 * ({@code /{slug}/film-N.html}). Films are intentionally a separate table from {@code
 * jutsu_episode} — they don't have seasons, don't reuse episode numbering, and are surfaced as a
 * distinct section in the demo UI.
 *
 * <p>{@code paywalled} is a tri-state flag: {@code TRUE} means the catalog observed a Jutsu+ gate
 * (films are very often gated as {@code short-btn black}), {@code FALSE} means anonymous decode
 * worked at last fetch, {@code NULL} means we have not yet probed (the catalog crawl alone doesn't
 * tell us — it just lists films).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class JutsuFilm {

    private String slug;
    private int filmIndex;
    private String label;
    private String relativeUrl;
    private Boolean paywalled;
    private LocalDateTime discoveredAt;
    private LocalDateTime lastSeenAt;
}
