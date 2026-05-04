package com.orinuno.jutsu.catalog;

import java.util.List;

/**
 * One AJAX page of catalog entries plus the {@code anime_page_next} flag the website uses for its
 * infinite-scroll terminus signal.
 *
 * @param entries up to 30 entries (jut.su's page size) in DOM order; never null
 * @param page 1-based page number that produced this response
 * @param hasMore mirror of the {@code var anime_page_next = true|false;} JS line at the top of the
 *     partial response. {@code true} ⇒ caller may safely fetch page+1; {@code false} ⇒ this is the
 *     terminal page.
 */
public record JutsuCatalogPage(List<JutsuCatalogEntry> entries, int page, boolean hasMore) {

    public JutsuCatalogPage {
        if (page < 1) throw new IllegalArgumentException("page must be ≥ 1, got " + page);
        entries = entries == null ? List.of() : List.copyOf(entries);
    }

    public static JutsuCatalogPage empty(int page) {
        return new JutsuCatalogPage(List.of(), page, false);
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    public int size() {
        return entries.size();
    }
}
