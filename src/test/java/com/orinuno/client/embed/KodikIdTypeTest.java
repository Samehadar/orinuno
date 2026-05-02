package com.orinuno.client.embed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class KodikIdTypeTest {

    @ParameterizedTest(name = "fromSlug({0}) → {1}")
    @CsvSource({
        "shikimori,SHIKIMORI",
        "Shikimori,SHIKIMORI",
        "  shikimori  ,SHIKIMORI",
        "kinopoisk,KINOPOISK",
        "imdb,IMDB",
        "mdl,MDL",
        "kodik,KODIK",
        "worldart_animation,WORLDART_ANIMATION",
        "worldart-animation,WORLDART_ANIMATION",
        "WORLDART-CINEMA,WORLDART_CINEMA"
    })
    @DisplayName("fromSlug accepts mixed case and kebab-case input")
    void fromSlugAcceptsAliases(String input, KodikIdType expected) {
        assertThat(KodikIdType.fromSlug(input)).isEqualTo(expected);
    }

    @Test
    @DisplayName("fromSlug rejects null and blank")
    void fromSlugRejectsBlank() {
        assertThatThrownBy(() -> KodikIdType.fromSlug(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("idType is required");
        assertThatThrownBy(() -> KodikIdType.fromSlug("  "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("fromSlug rejects unknown values with a hint listing supported slugs")
    void fromSlugRejectsUnknown() {
        assertThatThrownBy(() -> KodikIdType.fromSlug("anilist"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown idType")
                .hasMessageContaining("shikimori")
                .hasMessageContaining("worldart_cinema");
    }

    @ParameterizedTest(name = "{0} → kodikQueryKey={1}")
    @CsvSource({
        "SHIKIMORI,shikimoriID",
        "KINOPOISK,kinopoiskID",
        "IMDB,imdbID",
        "MDL,mdlID",
        "KODIK,ID",
        "WORLDART_ANIMATION,worldart_animation_id",
        "WORLDART_CINEMA,worldart_cinema_id"
    })
    @DisplayName("kodikQueryKey matches the AnimeParsers 1.16.1 reference")
    void kodikQueryKeysMatchUpstream(KodikIdType type, String expectedKey) {
        assertThat(type.getKodikQueryKey()).isEqualTo(expectedKey);
    }

    @Test
    @DisplayName("normalizeId adds tt prefix for IMDB when missing")
    void normalizeImdbAddsTtPrefix() {
        assertThat(KodikIdType.IMDB.normalizeId("0903747")).isEqualTo("tt0903747");
        assertThat(KodikIdType.IMDB.normalizeId("tt0903747")).isEqualTo("tt0903747");
        assertThat(KodikIdType.IMDB.normalizeId("  0903747  ")).isEqualTo("tt0903747");
    }

    @Test
    @DisplayName("normalizeId leaves non-IMDB types unchanged (apart from trim)")
    void normalizeNonImdbPassThrough() {
        assertThat(KodikIdType.SHIKIMORI.normalizeId("20")).isEqualTo("20");
        assertThat(KodikIdType.KODIK.normalizeId("serial-1234")).isEqualTo("serial-1234");
        assertThat(KodikIdType.WORLDART_ANIMATION.normalizeId(" 7659 ")).isEqualTo("7659");
    }

    @Test
    @DisplayName("normalizeId rejects null and blank input")
    void normalizeIdRejectsBlank() {
        assertThatThrownBy(() -> KodikIdType.SHIKIMORI.normalizeId(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> KodikIdType.SHIKIMORI.normalizeId("   "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("getSlug is the canonical snake_case form (Jackson @JsonValue)")
    void slugIsCanonical() {
        assertThat(KodikIdType.WORLDART_ANIMATION.getSlug()).isEqualTo("worldart_animation");
        assertThat(KodikIdType.WORLDART_CINEMA.getSlug()).isEqualTo("worldart_cinema");
    }
}
