package com.orinuno.jutsu.catalog;

import com.orinuno.jutsu.filter.JutsuCatalogFilter;
import com.orinuno.jutsu.filter.JutsuFilterSlugger;
import jakarta.annotation.Nullable;

/**
 * Describes one catalog AJAX call. The website encodes filtering/sort in the URL path ({@code
 * /anime/{cats}/{years}/{sort}/}) and the page number / search query in the form body ({@code
 * ajax_load=yes&start_from_page={N}&show_search={query}&anime_of_user=}). This record captures both
 * halves so the client can issue one POST per page.
 *
 * <p>Construction is via factory methods to enforce sensible defaults (page ≥ 1, never-null filter,
 * search query trimmed) — direct use of the canonical constructor is supported but usually more
 * verbose.
 *
 * @param filter the catalog filter; never null. Use {@link JutsuCatalogFilter#empty()} for "no
 *     filter".
 * @param page 1-based start_from_page value
 * @param searchQuery {@code show_search} form field; null/blank means "no search". Composes with
 *     {@link #filter} — both can be active at the same time.
 * @param pathPrefix escape hatch overriding the slug generated from {@link #filter}. Used only by
 *     advanced callers who already know the URL they want; setting this skips {@link
 *     JutsuFilterSlugger} entirely.
 */
public record JutsuCatalogRequest(
        JutsuCatalogFilter filter,
        int page,
        @Nullable String searchQuery,
        @Nullable String pathPrefix) {

    public JutsuCatalogRequest {
        if (filter == null) throw new IllegalArgumentException("filter must not be null");
        if (page < 1) throw new IllegalArgumentException("page must be ≥ 1, got " + page);
        searchQuery = normaliseQuery(searchQuery);
        pathPrefix = pathPrefix == null || pathPrefix.isBlank() ? null : pathPrefix.trim();
    }

    /** Browse the unfiltered catalog at the given page. */
    public static JutsuCatalogRequest unfiltered(int page) {
        return new JutsuCatalogRequest(JutsuCatalogFilter.empty(), page, null, null);
    }

    /** Browse a filtered catalog at the given page. */
    public static JutsuCatalogRequest filtered(JutsuCatalogFilter filter, int page) {
        return new JutsuCatalogRequest(filter, page, null, null);
    }

    /** Title search at the given page (no filter). */
    public static JutsuCatalogRequest search(String query, int page) {
        return new JutsuCatalogRequest(JutsuCatalogFilter.empty(), page, query, null);
    }

    /** Title search constrained by a filter. */
    public static JutsuCatalogRequest searchInFilter(
            JutsuCatalogFilter filter, String query, int page) {
        return new JutsuCatalogRequest(filter, page, query, null);
    }

    /**
     * Escape hatch: hit a literal path the caller already knows is valid (e.g., a stored URL from
     * an external source). The filter is ignored for URL composition but kept on the record so the
     * parser can still attach it as metadata.
     */
    public static JutsuCatalogRequest withPathPrefix(String pathPrefix, int page) {
        return new JutsuCatalogRequest(JutsuCatalogFilter.empty(), page, null, pathPrefix);
    }

    /**
     * Resolve the URL path component (relative, with leading {@code /anime/} and trailing {@code
     * /}) — either the explicit override or the filter-derived slug.
     */
    public String resolvePath() {
        if (pathPrefix != null) return ensureLeadingAndTrailingSlash(pathPrefix);
        return JutsuFilterSlugger.composePath(filter);
    }

    /** {@code true} when this request carries a non-blank search query. */
    public boolean hasSearch() {
        return searchQuery != null && !searchQuery.isBlank();
    }

    private static String ensureLeadingAndTrailingSlash(String path) {
        String p = path;
        if (!p.startsWith("/")) p = "/" + p;
        if (!p.endsWith("/")) p = p + "/";
        return p;
    }

    private static String normaliseQuery(@Nullable String input) {
        if (input == null) return null;
        String trimmed = input.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
