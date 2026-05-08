package com.orinuno.jutsu.info;

import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Russian age-rating classifier as displayed on jut.su info pages ({@code 0+}, {@code 6+}, {@code
 * 12+}, {@code 16+}, {@code 18+}).
 *
 * <p>Sourced from the {@code <span class="age_rating_all age_rating_NN">NN<small>+</small></span>}
 * marker inside the {@code under_video_additional} info block. The class name carries the
 * authoritative age (e.g. {@code age_rating_18}) so we read that rather than the rendered text.
 *
 * <p>The wire form ({@link #wire()}) is what the API DTO ships and what the L1 cache persists — use
 * {@link #fromAge(int)} or {@link #fromWire(String)} on the inbound path.
 */
public enum JutsuAgeRating {
    RATING_0(0, "0+"),
    RATING_6(6, "6+"),
    RATING_12(12, "12+"),
    RATING_16(16, "16+"),
    RATING_18(18, "18+");

    private static final Map<Integer, JutsuAgeRating> BY_AGE =
            Arrays.stream(values())
                    .collect(Collectors.toUnmodifiableMap(JutsuAgeRating::age, r -> r));
    private static final Map<String, JutsuAgeRating> BY_WIRE =
            Arrays.stream(values())
                    .collect(Collectors.toUnmodifiableMap(JutsuAgeRating::wire, r -> r));

    private final int age;
    private final String wire;

    JutsuAgeRating(int age, String wire) {
        this.age = age;
        this.wire = wire;
    }

    public int age() {
        return age;
    }

    public String wire() {
        return wire;
    }

    /**
     * Resolve from a numeric age (e.g. {@code 18} → {@link #RATING_18}). Unknown ages return empty;
     * the parser uses this to gracefully ignore drift (jut.su adding a new {@code age_rating_25}
     * class wouldn't crash the parse, just leave the rating unset).
     */
    public static Optional<JutsuAgeRating> fromAge(int age) {
        return Optional.ofNullable(BY_AGE.get(age));
    }

    public static Optional<JutsuAgeRating> fromWire(String wire) {
        if (wire == null || wire.isBlank()) return Optional.empty();
        return Optional.ofNullable(BY_WIRE.get(wire.trim()));
    }
}
