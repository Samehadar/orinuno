package com.orinuno.client.dto;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Verifies the API-7 fluent type-shortcut builder methods on {@link KodikSearchRequest}, {@link
 * KodikListRequest}, and {@link KodikReferenceRequest}. The shortcuts are the primary "happy path"
 * for callers — if Lombok's generated builder ever silently swallows an override, these tests will
 * catch it.
 */
class KodikRequestBuilderShortcutsTest {

    @Test
    void searchAnimeShortcutSetsBothAnimeKinds() {
        KodikSearchRequest req = KodikSearchRequest.builder().title("Naruto").anime().build();

        assertThat(req.getTypes()).isEqualTo("anime-serial,anime-movie");
        assertThat(req.getTitle()).isEqualTo("Naruto");
    }

    @Test
    void searchSerialsShortcutSetsAllSerialKinds() {
        KodikSearchRequest req = KodikSearchRequest.builder().serials().build();
        assertThat(req.getTypes()).isEqualTo("anime-serial,foreign-serial,russian-serial");
    }

    @Test
    void searchMoviesShortcutSetsAllMovieKinds() {
        KodikSearchRequest req = KodikSearchRequest.builder().movies().build();
        assertThat(req.getTypes()).isEqualTo("anime-movie,foreign-movie,russian-movie");
    }

    @Test
    void searchCartoonsShortcutSetsAllCartoonKinds() {
        KodikSearchRequest req = KodikSearchRequest.builder().cartoons().build();
        assertThat(req.getTypes()).isEqualTo("russian-cartoon,foreign-cartoon,soviet-cartoon");
    }

    @Test
    void searchTypesVarargOverridesPreviousValue() {
        KodikSearchRequest req =
                KodikSearchRequest.builder()
                        .anime()
                        .types(KodikType.FOREIGN_MOVIE, KodikType.RUSSIAN_MOVIE)
                        .build();
        assertThat(req.getTypes()).isEqualTo("foreign-movie,russian-movie");
    }

    @Test
    void searchStringTypesStillWorksForBackwardsCompat() {
        KodikSearchRequest req = KodikSearchRequest.builder().types("custom-type-name").build();
        assertThat(req.getTypes()).isEqualTo("custom-type-name");
    }

    @Test
    void listAnimeShortcutSetsBothAnimeKinds() {
        KodikListRequest req = KodikListRequest.builder().anime().limit(10).build();

        assertThat(req.getTypes()).isEqualTo("anime-serial,anime-movie");
        assertThat(req.getLimit()).isEqualTo(10);
    }

    @Test
    void listMoviesShortcutSetsAllMovieKinds() {
        KodikListRequest req = KodikListRequest.builder().movies().build();
        assertThat(req.getTypes()).isEqualTo("anime-movie,foreign-movie,russian-movie");
    }

    @Test
    void listCartoonsShortcutSetsAllCartoonKinds() {
        KodikListRequest req = KodikListRequest.builder().cartoons().build();
        assertThat(req.getTypes()).isEqualTo("russian-cartoon,foreign-cartoon,soviet-cartoon");
    }

    @Test
    void referenceTypeShortcutSetsSingleType() {
        KodikReferenceRequest req =
                KodikReferenceRequest.builder().type(KodikType.ANIME_SERIAL).build();
        assertThat(req.getTypes()).isEqualTo("anime-serial");
    }

    @Test
    void referenceTypeShortcutNullClearsField() {
        KodikReferenceRequest req = KodikReferenceRequest.builder().type(null).build();
        assertThat(req.getTypes()).isNull();
    }

    @Test
    void referenceTypesVarargJoinsMultiple() {
        KodikReferenceRequest req =
                KodikReferenceRequest.builder()
                        .types(KodikType.ANIME_SERIAL, KodikType.ANIME_MOVIE)
                        .build();
        assertThat(req.getTypes()).isEqualTo("anime-serial,anime-movie");
    }
}
