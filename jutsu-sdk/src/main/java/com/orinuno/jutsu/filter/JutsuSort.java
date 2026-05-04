package com.orinuno.jutsu.filter;

import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Sort options on jut.su's filter form. {@link #BY_RATING} is the default — the website's
 * JavaScript intentionally <em>elides</em> it from the URL. The {@code JutsuFilterSlugger}
 * preserves that behaviour: a filter with {@code BY_RATING} produces a URL with no sort segment,
 * matching what a fresh form submission generates.
 *
 * <p>Slug values:
 *
 * <ul>
 *   <li>{@link #BY_RATING} → empty string (segment omitted)
 *   <li>{@link #BY_NAME} → {@code order-by-name}
 *   <li>{@link #BY_EPISODE_COUNT} → {@code order-by-count}
 *   <li>{@link #BY_RELEASE_DATE} → {@code order-by-date}
 *   <li>{@link #BY_DATE_ADDED} → {@code order-by-add}
 * </ul>
 */
public enum JutsuSort {
    /**
     * Default sort. Elided from URLs by the website JS — {@link #slug()} returns the empty string
     * to make slug composition trivial.
     */
    BY_RATING("", "По рейтингу", true),
    BY_NAME("order-by-name", "По алфавиту", false),
    BY_EPISODE_COUNT("order-by-count", "По кол-ву серий", false),
    BY_RELEASE_DATE("order-by-date", "По году выхода", false),
    BY_DATE_ADDED("order-by-add", "По дате добавл.", false);

    private static final Map<String, JutsuSort> BY_SLUG =
            Arrays.stream(values())
                    .filter(s -> !s.slug.isEmpty())
                    .collect(Collectors.toUnmodifiableMap(JutsuSort::slug, s -> s));

    private final String slug;
    private final String label;
    private final boolean elided;

    JutsuSort(String slug, String label, boolean elided) {
        this.slug = slug;
        this.label = label;
        this.elided = elided;
    }

    public String slug() {
        return slug;
    }

    public String label() {
        return label;
    }

    /**
     * {@code true} for the default sort that the website does not include in the URL. Slug
     * composition treats this as "no segment".
     */
    public boolean isElided() {
        return elided;
    }

    /**
     * Lookup by URL slug. Returns empty for the empty / null slug — callers should treat that as
     * {@link #BY_RATING} explicitly via {@link #BY_RATING}, since the empty string is ambiguous (no
     * sort vs. default sort).
     */
    public static Optional<JutsuSort> fromSlug(String slug) {
        if (slug == null || slug.isBlank()) return Optional.empty();
        return Optional.ofNullable(BY_SLUG.get(slug.toLowerCase(Locale.ROOT)));
    }
}
