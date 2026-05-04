package com.orinuno.jutsu.filter;

import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Year buckets on jut.su's filter form. Includes the special "ongoing" bucket and the historical
 * ranges. Declaration order mirrors the live form (newest first, then descending ranges).
 *
 * <p>The slug for the pre-2000 bucket is {@code before2000} (no hyphen), <strong>not</strong>
 * {@code before-2000}. This caught us once during fixture capture (a manually-typed {@code
 * /anime/before-2000/} URL returned HTTP 302), so it's pinned by enum value.
 */
public enum JutsuYear {
    ONGOING("ongoing", "Онгоинг"),
    Y_2026("2026", "2026"),
    Y_2025("2025", "2025"),
    Y_2024("2024", "2024"),
    Y_2015_2023("2015-2023", "2015-2023"),
    Y_2008_2014("2008-2014", "2008-2014"),
    Y_2000_2007("2000-2007", "2000-2007"),
    BEFORE_2000("before2000", "до 2000");

    private static final Map<String, JutsuYear> BY_SLUG =
            Arrays.stream(values()).collect(Collectors.toUnmodifiableMap(JutsuYear::slug, y -> y));

    private final String slug;
    private final String label;

    JutsuYear(String slug, String label) {
        this.slug = slug;
        this.label = label;
    }

    public String slug() {
        return slug;
    }

    public String label() {
        return label;
    }

    public static Optional<JutsuYear> fromSlug(String slug) {
        if (slug == null || slug.isBlank()) return Optional.empty();
        return Optional.ofNullable(BY_SLUG.get(slug.toLowerCase(Locale.ROOT)));
    }
}
