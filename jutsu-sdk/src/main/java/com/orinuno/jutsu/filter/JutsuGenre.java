package com.orinuno.jutsu.filter;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * The "genre" radio buttons on jut.su's <em>Выбрать категории</em> form. Every value pairs a URL
 * slug (used in the {@code /anime/{slug}/} composite path) with the Russian label rendered next to
 * the radio button.
 *
 * <p>Declaration order mirrors the live form's DOM order on 2026-05-04. The {@code
 * JutsuFilterSlugger} uses this order to emit deterministic slugs even when callers select genres
 * in any sequence — the URL backend treats the slug segment as a set, so this is purely about
 * stable cache keys and reproducible test output.
 *
 * <p>The drift baseline lives at {@code
 * jutsu-sdk/src/test/resources/jutsu/filter_form_manifest.json}; {@code JutsuFilterEnumDriftTest}
 * asserts that the live form's genre block is a superset of these values.
 */
public enum JutsuGenre {
    ADVENTURE("adventure", "Приключения"),
    ACTION("action", "Боевик"),
    COMEDY("comedy", "Комедия"),
    EVERYDAY("everyday", "Повседневность"),
    ROMANCE("romance", "Романтика"),
    DRAMA("drama", "Драма"),
    FANTASTIC("fantastic", "Фантастика"),
    FANTASY("fantasy", "Фэнтези"),
    MYSTIC("mystic", "Мистика"),
    DETECTIVE("detective", "Детектив"),
    THRILLER("thriller", "Триллер"),
    PSYCHOLOGY("psychology", "Психология");

    private static final Map<String, JutsuGenre> BY_SLUG =
            java.util.Arrays.stream(values())
                    .collect(Collectors.toUnmodifiableMap(JutsuGenre::slug, g -> g));

    private final String slug;
    private final String label;

    JutsuGenre(String slug, String label) {
        this.slug = slug;
        this.label = label;
    }

    public String slug() {
        return slug;
    }

    public String label() {
        return label;
    }

    /** Lookup by URL slug (case-insensitive). Returns empty when the slug is unknown. */
    public static Optional<JutsuGenre> fromSlug(String slug) {
        if (slug == null || slug.isBlank()) return Optional.empty();
        return Optional.ofNullable(BY_SLUG.get(slug.toLowerCase(Locale.ROOT)));
    }
}
