package com.orinuno.client.embed;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * External-id types accepted by the Kodik {@code /get-player} helper. Mirrors the seven id types
 * AnimeParsers 1.16.1 supports for {@code get_embed_link}.
 *
 * <p>Each enum value carries:
 *
 * <ul>
 *   <li>{@link #getSlug()} — the {@code snake_case} string used in our public REST URL ({@code
 *       /api/v1/embed/{idType}/{id}}). This is the JSON-serialised form.
 *   <li>{@link #getKodikQueryKey()} — the camelCase / mixed-case key Kodik expects in the upstream
 *       {@code /get-player} query string ({@code shikimoriID}, {@code mdlID}, {@code
 *       worldart_animation_id}, …). Hard-coded because Kodik mixes naming conventions across
 *       parameters.
 * </ul>
 *
 * <p>The {@code kodik} type expects values prefixed with the kind ({@code serial-1234} / {@code
 * movie-1234}) — same constraint as AnimeParsers; we forward unchanged.
 *
 * <p>The {@code imdb} type expects a {@code tt}-prefixed id; {@link #normalizeId(String)}
 * transparently adds the prefix when callers pass a bare numeric id, matching AnimeParsers' 1.16.1
 * behaviour.
 */
public enum KodikIdType {
    SHIKIMORI("shikimori", "shikimoriID"),
    KINOPOISK("kinopoisk", "kinopoiskID"),
    IMDB("imdb", "imdbID"),
    MDL("mdl", "mdlID"),
    KODIK("kodik", "ID"),
    WORLDART_ANIMATION("worldart_animation", "worldart_animation_id"),
    WORLDART_CINEMA("worldart_cinema", "worldart_cinema_id");

    private static final Map<String, KodikIdType> BY_SLUG =
            Map.copyOf(
                    Arrays.stream(values())
                            .collect(Collectors.toMap(KodikIdType::getSlug, t -> t)));

    private final String slug;
    private final String kodikQueryKey;

    KodikIdType(String slug, String kodikQueryKey) {
        this.slug = slug;
        this.kodikQueryKey = kodikQueryKey;
    }

    @JsonValue
    public String getSlug() {
        return slug;
    }

    public String getKodikQueryKey() {
        return kodikQueryKey;
    }

    /**
     * Parse a slug from REST input. Accepts mixed case and {@code -} separators ({@code
     * worldart-animation} → {@link #WORLDART_ANIMATION}) so callers don't need to hand-encode the
     * canonical {@code snake_case}.
     *
     * @throws IllegalArgumentException when the input does not match a known type
     */
    @JsonCreator
    public static KodikIdType fromSlug(String input) {
        if (input == null || input.isBlank()) {
            throw new IllegalArgumentException(
                    "idType is required, supported: " + supportedSlugs());
        }
        String normalized = input.trim().toLowerCase(Locale.ROOT).replace('-', '_');
        KodikIdType type = BY_SLUG.get(normalized);
        if (type == null) {
            throw new IllegalArgumentException(
                    "Unknown idType: '" + input + "', supported: " + supportedSlugs());
        }
        return type;
    }

    /**
     * Normalise the raw id before forwarding to Kodik. Currently only {@link #IMDB} performs
     * normalisation by adding the {@code tt} prefix when missing, mirroring AnimeParsers 1.16.1.
     *
     * @throws IllegalArgumentException when the id is null or blank
     */
    public String normalizeId(String rawId) {
        if (rawId == null || rawId.isBlank()) {
            throw new IllegalArgumentException("id must not be blank");
        }
        String trimmed = rawId.trim();
        if (this == IMDB && !trimmed.startsWith("tt")) {
            return "tt" + trimmed;
        }
        return trimmed;
    }

    public static String supportedSlugs() {
        return Arrays.stream(values()).map(KodikIdType::getSlug).collect(Collectors.joining(", "));
    }
}
