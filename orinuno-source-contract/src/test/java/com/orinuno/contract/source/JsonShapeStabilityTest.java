package com.orinuno.contract.source;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.PrettyPrinter;
import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Golden-file JSON shape stability test for {@link SourceCatalogEvent} and friends. Locks the wire
 * format so a change to one of the records (rename, reorder, type change) breaks this test before
 * it ships. The fixtures double as documentation: anyone wanting to know "what does a
 * MovieDiscovered look like on the wire?" reads the fixture file under {@code
 * src/test/resources/com/orinuno/contract/source/golden/}.
 *
 * <p>Each test does two assertions:
 *
 * <ol>
 *   <li>Serialise a hand-built event with a deterministic pretty-printer and compare against the
 *       fixture file (newline-normalised).
 *   <li>Round-trip deserialise the same event back into a {@link SourceCatalogEvent} and assert
 *       Jackson's {@code DEDUCTION} polymorphism lands on the right sealed variant.
 * </ol>
 *
 * <p>To regenerate fixtures after an intentional shape change, enable {@link #regenerateFixtures()}
 * temporarily (remove {@code @Disabled}), run the test once, and restore the annotation. Never
 * blank-rewrite fixtures from CI.
 */
class JsonShapeStabilityTest {

    private static final ObjectMapper MAPPER =
            JsonMapper.builder()
                    .findAndAddModules()
                    .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                    .disable(SerializationFeature.WRITE_DURATIONS_AS_TIMESTAMPS)
                    .enable(DeserializationFeature.READ_UNKNOWN_ENUM_VALUES_AS_NULL)
                    .build();

    private static final PrettyPrinter PRETTY = newPrinter();

    private static final Instant FIXED_NOW = Instant.parse("2026-05-10T12:34:56Z");

    private static final Provenance PROVENANCE =
            new Provenance(
                    "https://kodik-api.com/list",
                    FIXED_NOW,
                    "kodik-sdk-drift/0.1.0",
                    "lenient",
                    List.of("UNKNOWN_FIELD:material_data.deprecated_box"));

    @Test
    @DisplayName("TitleObserved JSON shape: identifier + info + provenance, deduction round-trips")
    void titleObservedShape() throws Exception {
        SourceCatalogEvent event = sampleTitleObserved();
        assertJsonShapeMatches(event, "title-observed.json");
        assertRoundTripsTo(event, SourceCatalogEvent.TitleObserved.class);
    }

    @Test
    @DisplayName("MovieDiscovered JSON shape: identifier + info + variant + provenance")
    void movieDiscoveredShape() throws Exception {
        SourceCatalogEvent event = sampleMovieDiscovered();
        assertJsonShapeMatches(event, "movie-discovered.json");
        assertRoundTripsTo(event, SourceCatalogEvent.MovieDiscovered.class);
    }

    @Test
    @DisplayName("SeriesDiscovered JSON shape: identifier + info + seasons + provenance")
    void seriesDiscoveredShape() throws Exception {
        SourceCatalogEvent event = sampleSeriesDiscovered();
        assertJsonShapeMatches(event, "series-discovered.json");
        assertRoundTripsTo(event, SourceCatalogEvent.SeriesDiscovered.class);
    }

    @Test
    @DisplayName("EpisodesUpdated JSON shape: identifier + seasons + provenance (no info field)")
    void episodesUpdatedShape() throws Exception {
        SourceCatalogEvent event = sampleEpisodesUpdated();
        assertJsonShapeMatches(event, "episodes-updated.json");
        assertRoundTripsTo(event, SourceCatalogEvent.EpisodesUpdated.class);
    }

    @Test
    @DisplayName("SourceRemoved JSON shape: identifier + provenance only")
    void sourceRemovedShape() throws Exception {
        SourceCatalogEvent event = sampleSourceRemoved();
        assertJsonShapeMatches(event, "source-removed.json");
        assertRoundTripsTo(event, SourceCatalogEvent.SourceRemoved.class);
    }

    @Test
    @DisplayName(
            "MovieDiscovered with poster URLs (posterUrl + bigPosterUrl + screenshotUrls +"
                    + " trailerUrls) — locks ARCH-0017-FOLLOWUP-POSTER wire shape")
    void movieDiscoveredWithPostersShape() throws Exception {
        SourceCatalogEvent event = sampleMovieDiscoveredWithPosters();
        assertJsonShapeMatches(event, "movie-discovered-with-posters.json");
        assertRoundTripsTo(event, SourceCatalogEvent.MovieDiscovered.class);
    }

    @Test
    @DisplayName(
            "ExternalIds with all fields populated round-trips and excludes null entries from"
                    + " JSON output (NON_NULL inclusion contract)")
    void externalIdsRoundTripExcludesNulls() throws Exception {
        ExternalIds full =
                ExternalIds.builder()
                        .kinopoiskId("283290")
                        .imdbId("tt0409591")
                        .shikimoriId("1")
                        .malId("20")
                        .anidbId("239")
                        .anilistId("21")
                        .tmdbId("46260")
                        .mdlId("naruto")
                        .worldartAnimationId("anime/naruto")
                        .worldartCinemaId(null)
                        .build();

        String json = MAPPER.writeValueAsString(full);
        assertThat(json).doesNotContain("worldartCinemaId");
        assertThat(json).contains("\"worldartAnimationId\":\"anime/naruto\"");

        ExternalIds parsed = MAPPER.readValue(json, ExternalIds.class);
        assertThat(parsed).isEqualTo(full);
    }

    /**
     * Disabled by default. To regenerate the fixtures after an intentional shape change: remove the
     * {@code @Disabled} annotation, run the test once, restore the annotation, and review the diff
     * in your VCS before committing.
     */
    @Disabled("Manual regeneration only — see Javadoc")
    @Test
    void regenerateFixtures() throws IOException {
        Path goldenDir = Paths.get("src/test/resources/com/orinuno/contract/source/golden");
        Files.createDirectories(goldenDir);
        writeFixture(goldenDir, "title-observed.json", sampleTitleObserved());
        writeFixture(goldenDir, "movie-discovered.json", sampleMovieDiscovered());
        writeFixture(
                goldenDir,
                "movie-discovered-with-posters.json",
                sampleMovieDiscoveredWithPosters());
        writeFixture(goldenDir, "series-discovered.json", sampleSeriesDiscovered());
        writeFixture(goldenDir, "episodes-updated.json", sampleEpisodesUpdated());
        writeFixture(goldenDir, "source-removed.json", sampleSourceRemoved());
    }

    private static SourceCatalogEvent.TitleObserved sampleTitleObserved() {
        return new SourceCatalogEvent.TitleObserved(
                SourceIdentifier.of("kodik", "movie-12345"),
                SourceContentInfo.builder()
                        .titleRu("Наруто")
                        .titleEn("Naruto")
                        .year(2002)
                        .kindHint(ContentKindHint.ANIME)
                        .externalIds(
                                ExternalIds.builder()
                                        .shikimoriId("1")
                                        .imdbId("tt0409591")
                                        .kinopoiskId("283290")
                                        .build())
                        .build(),
                PROVENANCE);
    }

    private static SourceCatalogEvent.MovieDiscovered sampleMovieDiscovered() {
        return new SourceCatalogEvent.MovieDiscovered(
                SourceIdentifier.of("kodik", "russian-movie-island-2006"),
                SourceContentInfo.builder()
                        .titleRu("Остров")
                        .year(2006)
                        .kindHint(ContentKindHint.MOVIE)
                        .externalIds(ExternalIds.builder().kinopoiskId("253245").build())
                        .build(),
                new SourceEpisodeVariant(
                        SourceIdentifier.of("kodik", "russian-movie-island-2006:variant-rus"),
                        "https://cdn.kodik-api.com/island.mp4",
                        "Russian dub",
                        "1080p",
                        Duration.ofMinutes(112),
                        null),
                PROVENANCE);
    }

    private static SourceCatalogEvent.MovieDiscovered sampleMovieDiscoveredWithPosters() {
        return new SourceCatalogEvent.MovieDiscovered(
                SourceIdentifier.of("kodik", "russian-movie-island-2006"),
                SourceContentInfo.builder()
                        .titleRu("Остров")
                        .year(2006)
                        .kindHint(ContentKindHint.MOVIE)
                        .externalIds(ExternalIds.builder().kinopoiskId("253245").build())
                        .posterUrl(
                                "https://st.kp.yandex.net/images/film_iphone/iphone360_253245.jpg")
                        .bigPosterUrl("https://st.kp.yandex.net/images/film_big/253245.jpg")
                        .screenshotUrls(
                                List.of(
                                        "https://i.kodik.biz/screenshots/253245/1.jpg",
                                        "https://i.kodik.biz/screenshots/253245/2.jpg"))
                        .trailerUrls(List.of("https://www.youtube.com/watch?v=island2006"))
                        .build(),
                new SourceEpisodeVariant(
                        SourceIdentifier.of("kodik", "russian-movie-island-2006:variant-rus"),
                        "https://cdn.kodik-api.com/island.mp4",
                        "Russian dub",
                        "1080p",
                        Duration.ofMinutes(112),
                        null),
                PROVENANCE);
    }

    private static SourceCatalogEvent.SeriesDiscovered sampleSeriesDiscovered() {
        return new SourceCatalogEvent.SeriesDiscovered(
                SourceIdentifier.of("jutsu", "naruto"),
                SourceContentInfo.builder()
                        .titleRu("Наруто")
                        .titleEn("Naruto")
                        .year(2002)
                        .kindHint(ContentKindHint.ANIME)
                        .externalIds(ExternalIds.empty())
                        .build(),
                List.of(
                        new SourceSeason(
                                "Сезон 1",
                                null,
                                null,
                                1,
                                List.of(
                                        new SourceEpisode(
                                                "Возвращение Какаси",
                                                null,
                                                null,
                                                Duration.ofMinutes(24),
                                                null,
                                                null,
                                                1,
                                                List.of())))),
                PROVENANCE);
    }

    private static SourceCatalogEvent.EpisodesUpdated sampleEpisodesUpdated() {
        return new SourceCatalogEvent.EpisodesUpdated(
                SourceIdentifier.of("jutsu", "naruto"),
                List.of(
                        new SourceSeason(
                                null,
                                null,
                                null,
                                1,
                                List.of(
                                        new SourceEpisode(
                                                "Эпизод 220",
                                                null,
                                                null,
                                                Duration.ofMinutes(24),
                                                "https://video.jut.su/naruto/220",
                                                null,
                                                220,
                                                List.of())))),
                PROVENANCE);
    }

    private static SourceCatalogEvent.SourceRemoved sampleSourceRemoved() {
        return new SourceCatalogEvent.SourceRemoved(
                SourceIdentifier.of("kodik", "movie-deprecated-99"), PROVENANCE);
    }

    private static void assertJsonShapeMatches(SourceCatalogEvent event, String fixtureName)
            throws IOException {
        String actual = MAPPER.writer(PRETTY).writeValueAsString(event);
        String expected = readFixture(fixtureName);
        assertThat(normalise(actual))
                .as(
                        "JSON shape for %s must match fixture %s",
                        event.getClass().getSimpleName(), fixtureName)
                .isEqualTo(normalise(expected));
    }

    private static <T extends SourceCatalogEvent> void assertRoundTripsTo(
            SourceCatalogEvent event, Class<T> expectedVariant) throws IOException {
        String json = MAPPER.writeValueAsString(event);
        SourceCatalogEvent parsed = MAPPER.readValue(json, SourceCatalogEvent.class);
        assertThat(parsed)
                .as("DEDUCTION must resolve %s to %s", json, expectedVariant.getSimpleName())
                .isInstanceOf(expectedVariant);
        assertThat(parsed).isEqualTo(event);
    }

    private static void writeFixture(Path goldenDir, String name, SourceCatalogEvent event)
            throws IOException {
        String json = MAPPER.writer(PRETTY).writeValueAsString(event);
        Files.writeString(goldenDir.resolve(name), json + "\n", StandardCharsets.UTF_8);
    }

    private static String readFixture(String name) throws IOException {
        String path = "/com/orinuno/contract/source/golden/" + name;
        try (InputStream in = JsonShapeStabilityTest.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("Missing fixture on classpath: " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String normalise(String value) {
        return value.replace("\r\n", "\n").trim();
    }

    private static PrettyPrinter newPrinter() {
        DefaultIndenter indenter = new DefaultIndenter("  ", "\n");
        DefaultPrettyPrinter printer = new DefaultPrettyPrinter();
        printer.indentObjectsWith(indenter);
        printer.indentArraysWith(indenter);
        return printer;
    }
}
