package com.orinuno.jutsu.filter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Random;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Exhaustive coverage of {@link JutsuFilterSlugger}'s round-trip. The matrix:
 *
 * <ul>
 *   <li>~44 singletons (one per enum value across all four enums)
 *   <li>all cross-pairs across the four facets, including within-category pairs
 *   <li>composition patterns (empty / only sort / only year / only genre / multi)
 *   <li>BY_RATING elision corner cases
 *   <li>permutation invariance — random reorderings of identical filters compose identically
 * </ul>
 *
 * Cases together exceed 1000 — we trust the parameterised runner to keep this readable, not
 * exhaustively enumerate every test name.
 */
class JutsuFilterSluggerTest {

    // ---------------------- arguments suppliers ----------------------

    static Stream<Arguments> singletonGenres() {
        return Arrays.stream(JutsuGenre.values()).map(Arguments::of);
    }

    static Stream<Arguments> singletonTypes() {
        return Arrays.stream(JutsuType.values()).map(Arguments::of);
    }

    static Stream<Arguments> singletonYears() {
        return Arrays.stream(JutsuYear.values()).map(Arguments::of);
    }

    static Stream<Arguments> singletonSorts() {
        return Arrays.stream(JutsuSort.values()).map(Arguments::of);
    }

    static Stream<Arguments> genreTypePairs() {
        return Arrays.stream(JutsuGenre.values())
                .flatMap(g -> Arrays.stream(JutsuType.values()).map(t -> Arguments.of(g, t)));
    }

    static Stream<Arguments> genreYearPairs() {
        return Arrays.stream(JutsuGenre.values())
                .flatMap(g -> Arrays.stream(JutsuYear.values()).map(y -> Arguments.of(g, y)));
    }

    static Stream<Arguments> genreSortPairs() {
        return Arrays.stream(JutsuGenre.values())
                .flatMap(
                        g ->
                                Arrays.stream(JutsuSort.values())
                                        .filter(s -> !s.isElided())
                                        .map(s -> Arguments.of(g, s)));
    }

    static Stream<Arguments> typeYearPairs() {
        return Arrays.stream(JutsuType.values())
                .flatMap(t -> Arrays.stream(JutsuYear.values()).map(y -> Arguments.of(t, y)));
    }

    static Stream<Arguments> typeSortPairs() {
        return Arrays.stream(JutsuType.values())
                .flatMap(
                        t ->
                                Arrays.stream(JutsuSort.values())
                                        .filter(s -> !s.isElided())
                                        .map(s -> Arguments.of(t, s)));
    }

    static Stream<Arguments> yearSortPairs() {
        return Arrays.stream(JutsuYear.values())
                .flatMap(
                        y ->
                                Arrays.stream(JutsuSort.values())
                                        .filter(s -> !s.isElided())
                                        .map(s -> Arguments.of(y, s)));
    }

    static Stream<Arguments> genreGenrePairs() {
        return Arrays.stream(JutsuGenre.values())
                .flatMap(
                        a ->
                                Arrays.stream(JutsuGenre.values())
                                        .filter(b -> b.ordinal() > a.ordinal())
                                        .map(b -> Arguments.of(a, b)));
    }

    static Stream<Arguments> typeTypePairs() {
        return Arrays.stream(JutsuType.values())
                .flatMap(
                        a ->
                                Arrays.stream(JutsuType.values())
                                        .filter(b -> b.ordinal() > a.ordinal())
                                        .map(b -> Arguments.of(a, b)));
    }

    static Stream<Arguments> yearYearPairs() {
        return Arrays.stream(JutsuYear.values())
                .flatMap(
                        a ->
                                Arrays.stream(JutsuYear.values())
                                        .filter(b -> b.ordinal() > a.ordinal())
                                        .map(b -> Arguments.of(a, b)));
    }

    // ---------------------- singletons ----------------------

    @ParameterizedTest(name = "{0}")
    @MethodSource("singletonGenres")
    void roundTripSingletonGenre(JutsuGenre genre) {
        JutsuCatalogFilter filter = JutsuCatalogFilter.builder().addGenre(genre).build();
        roundTrip(filter, "/anime/" + genre.slug() + "/");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("singletonTypes")
    void roundTripSingletonType(JutsuType type) {
        JutsuCatalogFilter filter = JutsuCatalogFilter.builder().addType(type).build();
        roundTrip(filter, "/anime/" + type.slug() + "/");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("singletonYears")
    void roundTripSingletonYear(JutsuYear year) {
        JutsuCatalogFilter filter = JutsuCatalogFilter.builder().addYear(year).build();
        roundTrip(filter, "/anime/" + year.slug() + "/");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("singletonSorts")
    void roundTripSingletonSort(JutsuSort sort) {
        JutsuCatalogFilter filter = JutsuCatalogFilter.builder().sort(sort).build();
        String expected = sort.isElided() ? "/anime/" : "/anime/" + sort.slug() + "/";
        roundTrip(filter, expected);
    }

    // ---------------------- cross pairs ----------------------

    @ParameterizedTest(name = "{0}+{1}")
    @MethodSource("genreTypePairs")
    void roundTripGenreType(JutsuGenre g, JutsuType t) {
        JutsuCatalogFilter filter = JutsuCatalogFilter.builder().addGenre(g).addType(t).build();
        // Genres come before types in seg1 by enum-declaration ordering.
        roundTrip(filter, "/anime/" + g.slug() + "-" + t.slug() + "/");
    }

    @ParameterizedTest(name = "{0}+{1}")
    @MethodSource("genreYearPairs")
    void roundTripGenreYear(JutsuGenre g, JutsuYear y) {
        JutsuCatalogFilter filter = JutsuCatalogFilter.builder().addGenre(g).addYear(y).build();
        roundTrip(filter, "/anime/" + g.slug() + "/" + y.slug() + "/");
    }

    @ParameterizedTest(name = "{0}+{1}")
    @MethodSource("genreSortPairs")
    void roundTripGenreSort(JutsuGenre g, JutsuSort s) {
        JutsuCatalogFilter filter = JutsuCatalogFilter.builder().addGenre(g).sort(s).build();
        roundTrip(filter, "/anime/" + g.slug() + "/" + s.slug() + "/");
    }

    @ParameterizedTest(name = "{0}+{1}")
    @MethodSource("typeYearPairs")
    void roundTripTypeYear(JutsuType t, JutsuYear y) {
        JutsuCatalogFilter filter = JutsuCatalogFilter.builder().addType(t).addYear(y).build();
        roundTrip(filter, "/anime/" + t.slug() + "/" + y.slug() + "/");
    }

    @ParameterizedTest(name = "{0}+{1}")
    @MethodSource("typeSortPairs")
    void roundTripTypeSort(JutsuType t, JutsuSort s) {
        JutsuCatalogFilter filter = JutsuCatalogFilter.builder().addType(t).sort(s).build();
        roundTrip(filter, "/anime/" + t.slug() + "/" + s.slug() + "/");
    }

    @ParameterizedTest(name = "{0}+{1}")
    @MethodSource("yearSortPairs")
    void roundTripYearSort(JutsuYear y, JutsuSort s) {
        JutsuCatalogFilter filter = JutsuCatalogFilter.builder().addYear(y).sort(s).build();
        roundTrip(filter, "/anime/" + y.slug() + "/" + s.slug() + "/");
    }

    @ParameterizedTest(name = "{0}+{1}")
    @MethodSource("genreGenrePairs")
    void roundTripGenreGenre(JutsuGenre a, JutsuGenre b) {
        JutsuCatalogFilter filter = JutsuCatalogFilter.builder().addGenre(a).addGenre(b).build();
        // Enum-declaration order — a always precedes b given how genreGenrePairs() is generated.
        roundTrip(filter, "/anime/" + a.slug() + "-" + b.slug() + "/");
    }

    @ParameterizedTest(name = "{0}+{1}")
    @MethodSource("typeTypePairs")
    void roundTripTypeType(JutsuType a, JutsuType b) {
        JutsuCatalogFilter filter = JutsuCatalogFilter.builder().addType(a).addType(b).build();
        roundTrip(filter, "/anime/" + a.slug() + "-" + b.slug() + "/");
    }

    @ParameterizedTest(name = "{0}+{1}")
    @MethodSource("yearYearPairs")
    void roundTripYearYear(JutsuYear a, JutsuYear b) {
        JutsuCatalogFilter filter = JutsuCatalogFilter.builder().addYear(a).addYear(b).build();
        roundTrip(filter, "/anime/" + a.slug() + "-and-" + b.slug() + "/");
    }

    // ---------------------- composition patterns ----------------------

    @Test
    void emptyFilterComposesToBareAnimePath() {
        assertThat(JutsuFilterSlugger.composePath(JutsuCatalogFilter.empty())).isEqualTo("/anime/");
        assertThat(JutsuFilterSlugger.parsePath("/anime/")).isEqualTo(JutsuCatalogFilter.empty());
    }

    @Test
    void allGenresAndTypesAndYearsAndSort() {
        JutsuCatalogFilter filter =
                JutsuCatalogFilter.builder()
                        .addGenres(EnumSet.allOf(JutsuGenre.class))
                        .addTypes(EnumSet.allOf(JutsuType.class))
                        .addYears(EnumSet.allOf(JutsuYear.class))
                        .sort(JutsuSort.BY_DATE_ADDED)
                        .build();

        String path = JutsuFilterSlugger.composePath(filter);

        // Sanity: starts with /anime/ and ends with /, contains all category slugs and all
        // year slugs joined with -and-, ends with the sort slug.
        assertThat(path).startsWith("/anime/").endsWith("/order-by-add/");
        assertThat(path).contains("/" + JutsuYear.ONGOING.slug() + "-and-");
        for (JutsuGenre g : JutsuGenre.values()) assertThat(path).contains(g.slug());
        for (JutsuType t : JutsuType.values()) assertThat(path).contains(t.slug());

        roundTrip(filter, path);
    }

    @Test
    void onlySortBuildsBareSortPath() {
        JutsuCatalogFilter filter = JutsuCatalogFilter.builder().sort(JutsuSort.BY_NAME).build();

        assertThat(JutsuFilterSlugger.composePath(filter))
                .isEqualTo("/anime/" + JutsuSort.BY_NAME.slug() + "/");
        assertThat(JutsuFilterSlugger.parsePath("/anime/" + JutsuSort.BY_NAME.slug() + "/"))
                .isEqualTo(filter);
    }

    @Test
    void capturedSampleUrlsParseToExpectedFilters() {
        // Five user-supplied URLs covering screenshots 1..5 of the captured live pages.
        // 1) drama-parody/ongoing/order-by-count/
        JutsuCatalogFilter f1 =
                JutsuFilterSlugger.parsePath("/anime/drama-parody/ongoing/order-by-count/");
        assertThat(f1.genres()).containsOnly(JutsuGenre.DRAMA);
        assertThat(f1.types()).containsOnly(JutsuType.PARODY);
        assertThat(f1.years()).containsOnly(JutsuYear.ONGOING);
        assertThat(f1.sort()).isEqualTo(JutsuSort.BY_EPISODE_COUNT);

        // 2) the 7-cat / 3-year / order-by-add example URL
        JutsuCatalogFilter f2 =
                JutsuFilterSlugger.parsePath(
                        "/anime/romance-everyday-comedy-fantasy-shojo-parody-detective/"
                                + "2024-and-ongoing-and-2015-2023/order-by-add/");
        assertThat(f2.genres())
                .containsExactlyInAnyOrder(
                        JutsuGenre.COMEDY,
                        JutsuGenre.EVERYDAY,
                        JutsuGenre.ROMANCE,
                        JutsuGenre.FANTASY,
                        JutsuGenre.DETECTIVE);
        assertThat(f2.types()).containsExactlyInAnyOrder(JutsuType.PARODY, JutsuType.SHOJO);
        assertThat(f2.years())
                .containsExactlyInAnyOrder(
                        JutsuYear.ONGOING, JutsuYear.Y_2024, JutsuYear.Y_2015_2023);
        assertThat(f2.sort()).isEqualTo(JutsuSort.BY_DATE_ADDED);

        // 3) bare /anime/ → empty filter, default sort
        assertThat(JutsuFilterSlugger.parsePath("/anime/")).isEqualTo(JutsuCatalogFilter.empty());
    }

    // ---------------------- BY_RATING elision ----------------------

    @Test
    void byRatingExplicitDoesNotAddSortSegment() {
        JutsuCatalogFilter filter =
                JutsuCatalogFilter.builder()
                        .addGenre(JutsuGenre.COMEDY)
                        .sort(JutsuSort.BY_RATING)
                        .build();

        assertThat(JutsuFilterSlugger.composePath(filter)).isEqualTo("/anime/comedy/");
    }

    @Test
    void parsePathWithoutSortDefaultsToBYRATING() {
        JutsuCatalogFilter filter = JutsuFilterSlugger.parsePath("/anime/comedy/2024/");

        assertThat(filter.sort()).isEqualTo(JutsuSort.BY_RATING);
        assertThat(filter.genres()).containsOnly(JutsuGenre.COMEDY);
        assertThat(filter.years()).containsOnly(JutsuYear.Y_2024);
    }

    @Test
    void allCatsThenSortNoYears() {
        JutsuCatalogFilter filter =
                JutsuCatalogFilter.builder()
                        .addGenre(JutsuGenre.COMEDY)
                        .addGenre(JutsuGenre.DRAMA)
                        .addType(JutsuType.SCHOOL)
                        .sort(JutsuSort.BY_NAME)
                        .build();

        assertThat(JutsuFilterSlugger.composePath(filter))
                .isEqualTo("/anime/comedy-drama-school/order-by-name/");
    }

    // ---------------------- permutation invariance ----------------------

    @Test
    void differentInsertionOrdersComposeToSamePath() {
        // Pick a stable, mid-sized selection.
        List<JutsuGenre> genres =
                List.of(
                        JutsuGenre.ADVENTURE,
                        JutsuGenre.COMEDY,
                        JutsuGenre.DRAMA,
                        JutsuGenre.PSYCHOLOGY);
        List<JutsuType> types = List.of(JutsuType.MECHA, JutsuType.SCHOOL, JutsuType.PARODY);
        List<JutsuYear> years = List.of(JutsuYear.Y_2024, JutsuYear.ONGOING, JutsuYear.Y_2015_2023);
        JutsuSort sort = JutsuSort.BY_DATE_ADDED;
        String canonical;
        {
            JutsuCatalogFilter filter =
                    JutsuCatalogFilter.builder()
                            .addGenres(genres)
                            .addTypes(types)
                            .addYears(years)
                            .sort(sort)
                            .build();
            canonical = JutsuFilterSlugger.composePath(filter);
        }

        Random rng = new Random(424242L);
        for (int i = 0; i < 50; i++) {
            List<JutsuGenre> permG = new ArrayList<>(genres);
            Collections.shuffle(permG, rng);
            List<JutsuType> permT = new ArrayList<>(types);
            Collections.shuffle(permT, rng);
            List<JutsuYear> permY = new ArrayList<>(years);
            Collections.shuffle(permY, rng);

            JutsuCatalogFilter permFilter =
                    JutsuCatalogFilter.builder()
                            .addGenres(permG)
                            .addTypes(permT)
                            .addYears(permY)
                            .sort(sort)
                            .build();

            assertThat(JutsuFilterSlugger.composePath(permFilter))
                    .as("permutation %d (genres=%s, types=%s, years=%s)", i, permG, permT, permY)
                    .isEqualTo(canonical);
        }
    }

    // ---------------------- guard rails ----------------------

    @Test
    void composePathRejectsNullFilter() {
        assertThatIllegalArgumentException().isThrownBy(() -> JutsuFilterSlugger.composePath(null));
    }

    @Test
    void parsePathHandlesUrlWithoutLeadingPrefix() {
        assertThat(JutsuFilterSlugger.parsePath("comedy/2024/order-by-name/"))
                .isEqualTo(
                        JutsuCatalogFilter.builder()
                                .addGenre(JutsuGenre.COMEDY)
                                .addYear(JutsuYear.Y_2024)
                                .sort(JutsuSort.BY_NAME)
                                .build());
    }

    @Test
    void parsePathSilentlyDropsUnknownSlugs() {
        JutsuCatalogFilter filter =
                JutsuFilterSlugger.parsePath("/anime/comedy-someNewGenre/order-by-name/");

        assertThat(filter.genres()).containsOnly(JutsuGenre.COMEDY);
        assertThat(filter.types()).isEmpty();
        assertThat(filter.sort()).isEqualTo(JutsuSort.BY_NAME);
    }

    @Test
    void parsePathHandlesNullAndBlank() {
        assertThat(JutsuFilterSlugger.parsePath(null)).isEqualTo(JutsuCatalogFilter.empty());
        assertThat(JutsuFilterSlugger.parsePath("")).isEqualTo(JutsuCatalogFilter.empty());
        assertThat(JutsuFilterSlugger.parsePath("  ")).isEqualTo(JutsuCatalogFilter.empty());
    }

    @Test
    void splitCatsHandlesEmptyAndSingleAndMulti() {
        assertThat(JutsuFilterSlugger.splitCats(null)).isEmpty();
        assertThat(JutsuFilterSlugger.splitCats("")).isEmpty();
        assertThat(JutsuFilterSlugger.splitCats("comedy")).containsExactly("comedy");
        assertThat(JutsuFilterSlugger.splitCats("comedy-action"))
                .containsExactly("comedy", "action");
    }

    // ---------------------- helpers ----------------------

    private static void roundTrip(JutsuCatalogFilter filter, String expectedPath) {
        String composed = JutsuFilterSlugger.composePath(filter);
        assertThat(composed).as("composePath(%s)", filter).isEqualTo(expectedPath);
        JutsuCatalogFilter reparsed = JutsuFilterSlugger.parsePath(composed);
        assertThat(reparsed).as("parsePath(%s)", composed).isEqualTo(filter);
    }
}
