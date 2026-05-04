package com.orinuno.jutsu.filter;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Composes and parses the URL slug grammar that jut.su uses for catalog filtering. The grammar
 * (extracted from live URLs and cross-checked against the filter form):
 *
 * <pre>{@code
 * /anime/                                                    # empty filter, default sort
 * /anime/{cats}/                                             # genres + types only
 * /anime/{cats}/{years}/                                     # + years
 * /anime/{cats}/{years}/{sort}/                              # full
 * /anime/{years}/                                            # only years
 * /anime/{years}/{sort}/                                     # years + sort
 * /anime/{sort}/                                             # only sort
 * }</pre>
 *
 * <p>Where:
 *
 * <ul>
 *   <li>{@code cats} = genre slugs followed by type slugs, joined with {@code -}, in
 *       enum-declaration order. Example: {@code comedy-romance-shojo-parody}.
 *   <li>{@code years} = year slugs joined with {@code -and-}, in enum-declaration order. Example:
 *       {@code ongoing-and-2024-and-2015-2023}.
 *   <li>{@code sort} = the sort enum's slug, except {@link JutsuSort#BY_RATING} is elided.
 * </ul>
 *
 * <p>Empty segments are dropped, not represented as empty path components — the website's URL
 * normaliser HTTP 302s any URL containing two consecutive slashes.
 *
 * <p>Composition is deterministic: equal filters produce equal paths. Parsing is best-effort and
 * tolerant of unknown slugs (returns an {@link Optional#empty()} for the slug, but still composes
 * the rest of the filter — useful for live tests that exercise jut.su URLs we may not have mirrored
 * in the enums yet).
 */
public final class JutsuFilterSlugger {

    public static final String YEAR_JOINER = "-and-";
    public static final String CATS_JOINER = "-";

    private JutsuFilterSlugger() {}

    /** Compose the full path including leading {@code /anime/} and trailing {@code /}. */
    public static String composePath(JutsuCatalogFilter filter) {
        if (filter == null) throw new IllegalArgumentException("filter must not be null");
        StringBuilder out = new StringBuilder("/anime/");
        appendSegments(out, filter);
        return out.toString();
    }

    /**
     * Compose only the segments after {@code /anime/}, e.g. {@code "comedy-romance/2024/"}. Returns
     * the empty string when the filter has no selections (caller is expected to handle the bare
     * {@code /anime/} case).
     */
    public static String composeSegments(JutsuCatalogFilter filter) {
        if (filter == null) throw new IllegalArgumentException("filter must not be null");
        StringBuilder out = new StringBuilder();
        appendSegments(out, filter);
        return out.toString();
    }

    private static void appendSegments(StringBuilder out, JutsuCatalogFilter filter) {
        String cats = composeCats(filter.genres(), filter.types());
        String years = composeYears(filter.years());
        String sort = filter.sort().slug();
        if (!cats.isEmpty()) {
            out.append(cats).append('/');
        }
        if (!years.isEmpty()) {
            out.append(years).append('/');
        }
        if (!sort.isEmpty()) {
            out.append(sort).append('/');
        }
    }

    /** {@code "comedy-romance-shojo-parody"} or empty string. */
    public static String composeCats(EnumSet<JutsuGenre> genres, EnumSet<JutsuType> types) {
        return composeCats((java.util.Set<JutsuGenre>) genres, (java.util.Set<JutsuType>) types);
    }

    public static String composeCats(
            java.util.Set<JutsuGenre> genres, java.util.Set<JutsuType> types) {
        if ((genres == null || genres.isEmpty()) && (types == null || types.isEmpty())) {
            return "";
        }
        // Build a fresh EnumSet so iteration order is enum-declaration regardless of how the
        // caller's set is implemented.
        EnumSet<JutsuGenre> g =
                genres == null || genres.isEmpty()
                        ? EnumSet.noneOf(JutsuGenre.class)
                        : EnumSet.copyOf(genres);
        EnumSet<JutsuType> t =
                types == null || types.isEmpty()
                        ? EnumSet.noneOf(JutsuType.class)
                        : EnumSet.copyOf(types);
        StringBuilder out = new StringBuilder();
        for (JutsuGenre genre : g) {
            if (!out.isEmpty()) out.append(CATS_JOINER);
            out.append(genre.slug());
        }
        for (JutsuType type : t) {
            if (!out.isEmpty()) out.append(CATS_JOINER);
            out.append(type.slug());
        }
        return out.toString();
    }

    /** {@code "ongoing-and-2024-and-2015-2023"} or empty string. */
    public static String composeYears(java.util.Set<JutsuYear> years) {
        if (years == null || years.isEmpty()) return "";
        EnumSet<JutsuYear> ordered = EnumSet.copyOf(years);
        StringBuilder out = new StringBuilder();
        for (JutsuYear year : ordered) {
            if (!out.isEmpty()) out.append(YEAR_JOINER);
            out.append(year.slug());
        }
        return out.toString();
    }

    /**
     * Parse a {@code /anime/{seg1}/{seg2}/{seg3}/} path into a {@link JutsuCatalogFilter}. Tolerant
     * of unknown slugs — they are silently dropped (use a {@link
     * com.orinuno.jutsu.drift.JutsuParserContext} for drift-aware variants).
     *
     * @param path the path component, with or without leading {@code /anime/} and trailing {@code
     *     /}
     * @return the parsed filter; never null. An empty path returns {@link
     *     JutsuCatalogFilter#empty()}.
     */
    public static JutsuCatalogFilter parsePath(String path) {
        if (path == null || path.isBlank()) return JutsuCatalogFilter.empty();
        String trimmed = path.trim();
        if (trimmed.startsWith("/anime/")) trimmed = trimmed.substring("/anime/".length());
        else if (trimmed.startsWith("anime/")) trimmed = trimmed.substring("anime/".length());
        if (trimmed.endsWith("/")) trimmed = trimmed.substring(0, trimmed.length() - 1);
        if (trimmed.isEmpty()) return JutsuCatalogFilter.empty();

        // The path can have 1..3 non-empty segments; slot them by inspecting the contents.
        String[] segments = trimmed.split("/");
        JutsuCatalogFilter.Builder builder = JutsuCatalogFilter.builder();
        for (String segment : segments) {
            if (segment.isEmpty()) continue;
            if (looksLikeYear(segment)) {
                applyYears(builder, segment);
                continue;
            }
            if (isSortSlug(segment)) {
                JutsuSort.fromSlug(segment).ifPresent(builder::sort);
                continue;
            }
            applyCats(builder, segment);
        }
        return builder.build();
    }

    private static boolean looksLikeYear(String segment) {
        // A year segment is one or more JutsuYear slugs joined by "-and-".
        // Year slugs all start with a digit (e.g., "2024", "2015-2023") OR equal "ongoing" /
        // "before2000". Genre/type slugs never start with a digit and never equal those words.
        for (String part : segment.split(YEAR_JOINER)) {
            if (part.isEmpty()) return false;
            if (JutsuYear.fromSlug(part).isEmpty()) return false;
        }
        return true;
    }

    private static boolean isSortSlug(String segment) {
        return JutsuSort.fromSlug(segment).isPresent();
    }

    private static void applyYears(JutsuCatalogFilter.Builder builder, String segment) {
        for (String part : segment.split(YEAR_JOINER)) {
            JutsuYear.fromSlug(part).ifPresent(builder::addYear);
        }
    }

    private static void applyCats(JutsuCatalogFilter.Builder builder, String segment) {
        for (String part : segment.split(CATS_JOINER)) {
            if (part.isEmpty()) continue;
            // Year ranges contain a hyphen ("2015-2023"), so re-joining hyphens may have split a
            // valid year slug. Try the year first.
            String low = part.toLowerCase(Locale.ROOT);
            Optional<JutsuGenre> genre = JutsuGenre.fromSlug(low);
            if (genre.isPresent()) {
                builder.addGenre(genre.get());
                continue;
            }
            Optional<JutsuType> type = JutsuType.fromSlug(low);
            if (type.isPresent()) {
                builder.addType(type.get());
                continue;
            }
            // Unknown — silently drop. The drift-aware caller observes UNKNOWN_FILTER_SLUG
            // separately.
        }
    }

    /**
     * Convenience: parse a hyphen-joined cats segment (genre+type slugs) into ordered enum lists.
     * Used by the catalog parser tests.
     */
    public static List<String> splitCats(String segment) {
        if (segment == null || segment.isEmpty()) return List.of();
        List<String> result = new ArrayList<>();
        for (String part : segment.split(CATS_JOINER)) {
            if (!part.isEmpty()) result.add(part);
        }
        return result;
    }
}
