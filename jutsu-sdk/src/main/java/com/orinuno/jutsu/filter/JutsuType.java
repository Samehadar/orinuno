package com.orinuno.jutsu.filter;

import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * The "type" / sub-genre radio buttons on jut.su's filter form. Sits in a dedicated DOM block below
 * {@link JutsuGenre} but the URL composes both into a single slash-separated segment.
 *
 * <p>Declaration order mirrors the live form's DOM order on 2026-05-04 (left-to-right, top-down).
 */
public enum JutsuType {
    FIGHTING("fighting", "Боевые искусства"),
    VAMPIRE("vampire", "Вампиры"),
    MILITARY("military", "Военное"),
    DEMONS("demons", "Демоны"),
    GAME("game", "Игры"),
    HISTORICAL("historical", "История"),
    SPACE("space", "Космос"),
    MAGIC("magic", "Магия"),
    MECHA("mecha", "Меха"),
    MUSIC("music", "Музыка"),
    PARODY("parody", "Пародия"),
    POLICE("police", "Полиция"),
    SAMURAI("samurai", "Самураи"),
    SHOJO("shojo", "Сёдзё"),
    SHONEN("shonen", "Сёнен"),
    SPORT("sport", "Спорт"),
    SUPERPOWER("superpower", "Суперсила"),
    HORROR("horror", "Ужасы"),
    SCHOOL("school", "Школа");

    private static final Map<String, JutsuType> BY_SLUG =
            Arrays.stream(values()).collect(Collectors.toUnmodifiableMap(JutsuType::slug, t -> t));

    private final String slug;
    private final String label;

    JutsuType(String slug, String label) {
        this.slug = slug;
        this.label = label;
    }

    public String slug() {
        return slug;
    }

    public String label() {
        return label;
    }

    public static Optional<JutsuType> fromSlug(String slug) {
        if (slug == null || slug.isBlank()) return Optional.empty();
        return Optional.ofNullable(BY_SLUG.get(slug.toLowerCase(Locale.ROOT)));
    }
}
