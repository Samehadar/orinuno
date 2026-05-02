package com.orinuno.client.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class KodikTypeTest {

    @Test
    void apiValuesUseHyphenNotUnderscore() {
        assertThat(KodikType.ANIME_SERIAL.apiValue()).isEqualTo("anime-serial");
        assertThat(KodikType.ANIME_MOVIE.apiValue()).isEqualTo("anime-movie");
        assertThat(KodikType.FOREIGN_SERIAL.apiValue()).isEqualTo("foreign-serial");
        assertThat(KodikType.FOREIGN_MOVIE.apiValue()).isEqualTo("foreign-movie");
        assertThat(KodikType.RUSSIAN_SERIAL.apiValue()).isEqualTo("russian-serial");
        assertThat(KodikType.RUSSIAN_MOVIE.apiValue()).isEqualTo("russian-movie");
        assertThat(KodikType.RUSSIAN_CARTOON.apiValue()).isEqualTo("russian-cartoon");
        assertThat(KodikType.FOREIGN_CARTOON.apiValue()).isEqualTo("foreign-cartoon");
        assertThat(KodikType.SOVIET_CARTOON.apiValue()).isEqualTo("soviet-cartoon");
    }

    @Test
    void csvVarargsJoinsWithComma() {
        assertThat(KodikType.csv(KodikType.ANIME_SERIAL, KodikType.ANIME_MOVIE))
                .isEqualTo("anime-serial,anime-movie");
    }

    @Test
    void csvVarargsReturnsNullForEmptyOrNullInput() {
        assertThat(KodikType.csv((KodikType[]) null)).isNull();
        assertThat(KodikType.csv(new KodikType[0])).isNull();
    }

    @Test
    void csvIterableJoinsWithComma() {
        assertThat(KodikType.csv(List.of(KodikType.ANIME_SERIAL, KodikType.FOREIGN_MOVIE)))
                .isEqualTo("anime-serial,foreign-movie");
    }

    @Test
    void csvIterableReturnsNullForEmptyOrNull() {
        assertThat(KodikType.csv((Iterable<KodikType>) null)).isNull();
        assertThat(KodikType.csv(List.of())).isNull();
    }

    @Test
    void presetSetsContainExpectedKinds() {
        assertThat(KodikType.ANIME_KINDS)
                .containsExactlyInAnyOrder(KodikType.ANIME_SERIAL, KodikType.ANIME_MOVIE);
        assertThat(KodikType.SERIAL_KINDS)
                .containsExactlyInAnyOrder(
                        KodikType.ANIME_SERIAL, KodikType.FOREIGN_SERIAL, KodikType.RUSSIAN_SERIAL);
        assertThat(KodikType.MOVIE_KINDS)
                .containsExactlyInAnyOrder(
                        KodikType.FOREIGN_MOVIE, KodikType.RUSSIAN_MOVIE, KodikType.ANIME_MOVIE);
        assertThat(KodikType.CARTOON_KINDS)
                .containsExactlyInAnyOrder(
                        KodikType.RUSSIAN_CARTOON,
                        KodikType.FOREIGN_CARTOON,
                        KodikType.SOVIET_CARTOON);
    }
}
