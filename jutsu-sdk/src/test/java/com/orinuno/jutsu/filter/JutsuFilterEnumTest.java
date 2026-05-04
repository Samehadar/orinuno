package com.orinuno.jutsu.filter;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class JutsuFilterEnumTest {

    @Test
    void allGenreSlugsMatchExpectedSetAndAreUnique() {
        assertThat(Arrays.stream(JutsuGenre.values()).map(JutsuGenre::slug))
                .doesNotHaveDuplicates()
                .containsExactly(
                        "adventure",
                        "action",
                        "comedy",
                        "everyday",
                        "romance",
                        "drama",
                        "fantastic",
                        "fantasy",
                        "mystic",
                        "detective",
                        "thriller",
                        "psychology");
    }

    @Test
    void genreFromSlugIsCaseInsensitive() {
        assertThat(JutsuGenre.fromSlug("DRAMA")).contains(JutsuGenre.DRAMA);
        assertThat(JutsuGenre.fromSlug("Drama")).contains(JutsuGenre.DRAMA);
    }

    @Test
    void genreFromSlugRejectsBlankAndUnknown() {
        assertThat(JutsuGenre.fromSlug(null)).isEmpty();
        assertThat(JutsuGenre.fromSlug("")).isEmpty();
        assertThat(JutsuGenre.fromSlug("   ")).isEmpty();
        assertThat(JutsuGenre.fromSlug("nonexistent-genre")).isEmpty();
    }

    @Test
    void allTypeSlugsMatchExpectedSetAndAreUnique() {
        assertThat(Arrays.stream(JutsuType.values()).map(JutsuType::slug))
                .doesNotHaveDuplicates()
                .containsExactly(
                        "fighting",
                        "vampire",
                        "military",
                        "demons",
                        "game",
                        "historical",
                        "space",
                        "magic",
                        "mecha",
                        "music",
                        "parody",
                        "police",
                        "samurai",
                        "shojo",
                        "shonen",
                        "sport",
                        "superpower",
                        "horror",
                        "school");
    }

    @Test
    void allYearSlugsMatchExpectedSet() {
        assertThat(Arrays.stream(JutsuYear.values()).map(JutsuYear::slug))
                .doesNotHaveDuplicates()
                .containsExactly(
                        "ongoing",
                        "2026",
                        "2025",
                        "2024",
                        "2015-2023",
                        "2008-2014",
                        "2000-2007",
                        "before2000");
    }

    @Test
    void yearBefore2000HasNoHyphen() {
        // Pinning value: a manually-typed before-2000 returned HTTP 302.
        assertThat(JutsuYear.BEFORE_2000.slug()).isEqualTo("before2000");
        assertThat(JutsuYear.fromSlug("before-2000")).isEmpty();
        assertThat(JutsuYear.fromSlug("before2000")).contains(JutsuYear.BEFORE_2000);
    }

    @Test
    void allSortSlugsMatchExpectedSetWithRatingElided() {
        assertThat(JutsuSort.BY_RATING.slug()).isEmpty();
        assertThat(JutsuSort.BY_RATING.isElided()).isTrue();
        assertThat(JutsuSort.BY_NAME.isElided()).isFalse();
        assertThat(JutsuSort.BY_EPISODE_COUNT.isElided()).isFalse();
        assertThat(JutsuSort.BY_RELEASE_DATE.isElided()).isFalse();
        assertThat(JutsuSort.BY_DATE_ADDED.isElided()).isFalse();

        assertThat(Arrays.stream(JutsuSort.values()).map(JutsuSort::slug))
                .containsExactly(
                        "", "order-by-name", "order-by-count", "order-by-date", "order-by-add");
    }

    @Test
    void sortFromSlugIgnoresEmptyToAvoidAmbiguity() {
        // Empty slug means "no segment" — we cannot tell whether the caller meant BY_RATING or
        // simply hadn't picked a sort. Force callers to pass BY_RATING explicitly when needed.
        assertThat(JutsuSort.fromSlug("")).isEmpty();
        assertThat(JutsuSort.fromSlug(null)).isEmpty();
        assertThat(JutsuSort.fromSlug("   ")).isEmpty();
    }

    @Test
    void sortFromSlugIsCaseInsensitive() {
        Optional<JutsuSort> hit = JutsuSort.fromSlug("ORDER-BY-COUNT");

        assertThat(hit).contains(JutsuSort.BY_EPISODE_COUNT);
    }

    @Test
    void labelsAreNonBlankRussianStrings() {
        for (JutsuGenre v : JutsuGenre.values()) {
            assertThat(v.label()).isNotBlank();
        }
        for (JutsuType v : JutsuType.values()) {
            assertThat(v.label()).isNotBlank();
        }
        for (JutsuYear v : JutsuYear.values()) {
            assertThat(v.label()).isNotBlank();
        }
        for (JutsuSort v : JutsuSort.values()) {
            assertThat(v.label()).isNotBlank();
        }
    }

    @Test
    void noSlugCollisionsAcrossEnums() {
        // Backend treats /anime/{seg1} as a set, but the parser must distinguish genres from
        // types when extracting the URL — making sure no two enums share a slug protects that.
        var all = new HashSet<String>();
        for (JutsuGenre g : JutsuGenre.values()) {
            assertThat(all.add(g.slug())).as("duplicate slug %s", g.slug()).isTrue();
        }
        for (JutsuType t : JutsuType.values()) {
            assertThat(all.add(t.slug())).as("duplicate slug %s", t.slug()).isTrue();
        }
        for (JutsuYear y : JutsuYear.values()) {
            assertThat(all.add(y.slug())).as("duplicate slug %s", y.slug()).isTrue();
        }
        for (JutsuSort s : JutsuSort.values()) {
            if (s.slug().isEmpty()) continue;
            assertThat(all.add(s.slug())).as("duplicate slug %s", s.slug()).isTrue();
        }
    }
}
