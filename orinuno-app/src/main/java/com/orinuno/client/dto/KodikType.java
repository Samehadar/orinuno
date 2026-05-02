package com.orinuno.client.dto;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Strongly-typed enumeration of Kodik content types. Mirrors the literal {@code types} param
 * accepted by {@code /search}, {@code /list}, {@code /translations/v2}, {@code /years}, etc.
 *
 * <p>Use the helper factories ({@link #csv(KodikType...)}, {@link #ANIME_KINDS}, {@link
 * #SERIAL_KINDS}, {@link #MOVIE_KINDS}, {@link #CARTOON_KINDS}) to avoid hand-rolling
 * comma-separated strings — that's where typos historically crept in (e.g. {@code anime_serial}
 * with underscore vs. {@code anime-serial} with hyphen).
 *
 * <p>Source of truth: live probe 2026-05-02 — see docs/quirks-and-hacks.md.
 */
public enum KodikType {
    ANIME_SERIAL("anime-serial"),
    ANIME_MOVIE("anime-movie"),
    FOREIGN_SERIAL("foreign-serial"),
    FOREIGN_MOVIE("foreign-movie"),
    RUSSIAN_SERIAL("russian-serial"),
    RUSSIAN_MOVIE("russian-movie"),
    RUSSIAN_CARTOON("russian-cartoon"),
    FOREIGN_CARTOON("foreign-cartoon"),
    SOVIET_CARTOON("soviet-cartoon");

    /** All anime kinds (serial + movie). */
    public static final Set<KodikType> ANIME_KINDS = EnumSet.of(ANIME_SERIAL, ANIME_MOVIE);

    /** All serial kinds (anime + foreign + russian). */
    public static final Set<KodikType> SERIAL_KINDS =
            EnumSet.of(ANIME_SERIAL, FOREIGN_SERIAL, RUSSIAN_SERIAL);

    /** All non-anime feature-length kinds. */
    public static final Set<KodikType> MOVIE_KINDS =
            EnumSet.of(FOREIGN_MOVIE, RUSSIAN_MOVIE, ANIME_MOVIE);

    /** All cartoon kinds (russian + foreign + soviet). */
    public static final Set<KodikType> CARTOON_KINDS =
            EnumSet.of(RUSSIAN_CARTOON, FOREIGN_CARTOON, SOVIET_CARTOON);

    private final String apiValue;

    KodikType(String apiValue) {
        this.apiValue = apiValue;
    }

    /** The literal value Kodik expects in form-urlencoded / query-string params. */
    public String apiValue() {
        return apiValue;
    }

    /** Joins the given types into a comma-separated string suitable for the {@code types} param. */
    public static String csv(KodikType... types) {
        if (types == null || types.length == 0) return null;
        return Arrays.stream(types).map(KodikType::apiValue).collect(Collectors.joining(","));
    }

    public static String csv(Iterable<KodikType> types) {
        if (types == null) return null;
        StringBuilder sb = new StringBuilder();
        for (KodikType t : types) {
            if (sb.length() > 0) sb.append(',');
            sb.append(t.apiValue);
        }
        return sb.length() == 0 ? null : sb.toString();
    }
}
