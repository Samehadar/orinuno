package com.orinuno.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.orinuno.contract.source.ContentKindHint;
import com.orinuno.contract.source.SourceCatalogEvent;
import com.orinuno.model.KodikContent;
import com.orinuno.model.KodikEpisodeVariant;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SourceEventMapperTest {

    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2026-05-10T00:00:00Z"), ZoneOffset.UTC);

    @ParameterizedTest
    @ValueSource(
            strings = {"movie", "Movie", "MOVIE", "foreign-movie", "soviet-cartoon", "cartoon"})
    void movieTypesProduceMovieDiscovered(String type) {
        KodikContent content = baseContent(type);
        var variants = List.of(playableVariant(11L, 0, 0, "TR", "https://cdn/11.mp4"));

        var event = SourceEventMapper.toEvent(content, variants, FIXED_CLOCK);

        assertThat(event).isInstanceOf(SourceCatalogEvent.MovieDiscovered.class);
        var movie = (SourceCatalogEvent.MovieDiscovered) event;
        assertThat(movie.identifier().sourceType()).isEqualTo("kodik");
        assertThat(movie.identifier().sourceId()).isEqualTo("100");
        assertThat(movie.variant().mediaUrl()).isEqualTo("https://cdn/11.mp4");
        assertThat(movie.variant().identifier().sourceId()).isEqualTo("11");
        assertThat(movie.info().kindHint()).isEqualTo(ContentKindHint.MOVIE);
    }

    @ParameterizedTest
    @ValueSource(strings = {"anime-serial", "drama", "show"})
    void seriesTypesProduceSeriesDiscovered(String type) {
        KodikContent content = baseContent(type);
        var variants =
                List.of(
                        playableVariant(50L, 1, 1, "TR1", "https://cdn/50.mp4"),
                        playableVariant(51L, 1, 2, "TR2", "https://cdn/51.mp4"));

        var event = SourceEventMapper.toEvent(content, variants, FIXED_CLOCK);

        assertThat(event).isInstanceOf(SourceCatalogEvent.SeriesDiscovered.class);
        var serial = (SourceCatalogEvent.SeriesDiscovered) event;
        assertThat(serial.seasons()).hasSize(1);
        assertThat(serial.seasons().get(0).order()).isEqualTo(1);
        assertThat(serial.seasons().get(0).episodes()).hasSize(2);
        assertThat(serial.seasons().get(0).episodes().get(0).order()).isEqualTo(1);
        assertThat(serial.seasons().get(0).episodes().get(1).order()).isEqualTo(2);
    }

    @Test
    void variantsWithoutMp4LinkAreFilteredOut() {
        var content = baseContent("anime-serial");
        var variants =
                List.of(
                        playableVariant(50L, 1, 1, "TR1", "https://cdn/50.mp4"),
                        playableVariant(51L, 1, 1, "TR2", null),
                        playableVariant(52L, 1, 2, "TR3", "")); // blank link too

        var event = SourceEventMapper.toEvent(content, variants, FIXED_CLOCK);

        var serial = (SourceCatalogEvent.SeriesDiscovered) event;
        assertThat(serial.seasons()).hasSize(1);
        assertThat(serial.seasons().get(0).episodes()).hasSize(1);
        assertThat(serial.seasons().get(0).episodes().get(0).variants()).hasSize(1);
        assertThat(
                        serial.seasons()
                                .get(0)
                                .episodes()
                                .get(0)
                                .variants()
                                .get(0)
                                .identifier()
                                .sourceId())
                .isEqualTo("50");
    }

    @Test
    void contentWithoutPlayableVariantsBecomesTitleObserved() {
        var content = baseContent("anime-serial");
        var variants = List.of(playableVariant(50L, 1, 1, "TR1", null));

        var event = SourceEventMapper.toEvent(content, variants, FIXED_CLOCK);

        assertThat(event).isInstanceOf(SourceCatalogEvent.TitleObserved.class);
    }

    @Test
    void identifierUsesContentPkNotKodikId() {
        var content = baseContent("movie");
        content.setId(42L);
        content.setKodikId("some-kodik-raw");
        var variants = List.of(playableVariant(11L, 0, 0, "TR", "https://cdn/11.mp4"));

        var event = SourceEventMapper.toEvent(content, variants, FIXED_CLOCK);

        assertThat(event.identifier().sourceId()).isEqualTo("42");
    }

    @Test
    void externalIdsArePropagated() {
        var content = baseContent("movie");
        content.setKinopoiskId("12345");
        content.setImdbId("tt7654321");
        content.setShikimoriId("98765");
        var variants = List.of(playableVariant(11L, 0, 0, "TR", "https://cdn/11.mp4"));

        var event = SourceEventMapper.toEvent(content, variants, FIXED_CLOCK);

        assertThat(event).isInstanceOf(SourceCatalogEvent.MovieDiscovered.class);
        var info = ((SourceCatalogEvent.MovieDiscovered) event).info();
        assertThat(info.externalIds().kinopoiskId()).isEqualTo("12345");
        assertThat(info.externalIds().imdbId()).isEqualTo("tt7654321");
        assertThat(info.externalIds().shikimoriId()).isEqualTo("98765");
    }

    @Test
    void provenanceUsesUpdatedAtWhenPresent() {
        var fetchedAt = LocalDateTime.parse("2026-04-01T12:00:00");
        var content = baseContent("movie");
        content.setUpdatedAt(fetchedAt);
        var variants = List.of(playableVariant(11L, 0, 0, "TR", "https://cdn/11.mp4"));

        var event = SourceEventMapper.toEvent(content, variants, FIXED_CLOCK);

        assertThat(event.provenance().fetchedAt()).isEqualTo(fetchedAt.toInstant(ZoneOffset.UTC));
    }

    @Test
    void provenanceFallsBackToClockWhenUpdatedAtIsNull() {
        var content = baseContent("movie");
        content.setUpdatedAt(null);
        var variants = List.of(playableVariant(11L, 0, 0, "TR", "https://cdn/11.mp4"));

        var event = SourceEventMapper.toEvent(content, variants, FIXED_CLOCK);

        assertThat(event.provenance().fetchedAt()).isEqualTo(Instant.parse("2026-05-10T00:00:00Z"));
    }

    @Test
    void seasonsAreOrderedByNumber() {
        var content = baseContent("anime-serial");
        var variants =
                List.of(
                        playableVariant(70L, 3, 1, "TR3", "https://cdn/70.mp4"),
                        playableVariant(50L, 1, 1, "TR1", "https://cdn/50.mp4"),
                        playableVariant(60L, 2, 1, "TR2", "https://cdn/60.mp4"));

        var event = SourceEventMapper.toEvent(content, variants, FIXED_CLOCK);

        var serial = (SourceCatalogEvent.SeriesDiscovered) event;
        assertThat(serial.seasons()).extracting(s -> s.order()).containsExactly(1, 2, 3);
    }

    @Test
    void multipleVariantsPerEpisodeAreGrouped() {
        var content = baseContent("anime-serial");
        var variants =
                List.of(
                        playableVariant(50L, 1, 1, "Ru Dub", "https://cdn/50.mp4"),
                        playableVariant(51L, 1, 1, "Sub", "https://cdn/51.mp4"));

        var event = SourceEventMapper.toEvent(content, variants, FIXED_CLOCK);

        var serial = (SourceCatalogEvent.SeriesDiscovered) event;
        assertThat(serial.seasons().get(0).episodes()).hasSize(1);
        assertThat(serial.seasons().get(0).episodes().get(0).variants()).hasSize(2);
        assertThat(serial.seasons().get(0).episodes().get(0).variants())
                .extracting(v -> v.identifier().sourceId())
                .containsExactly("50", "51");
    }

    @Test
    void kindHintForAnimeContent() {
        var content = baseContent("anime-serial");
        var variants = List.of(playableVariant(50L, 1, 1, "TR", "https://cdn/50.mp4"));

        var event = SourceEventMapper.toEvent(content, variants, FIXED_CLOCK);

        var serial = (SourceCatalogEvent.SeriesDiscovered) event;
        assertThat(serial.info().kindHint()).isEqualTo(ContentKindHint.ANIME);
    }

    @Test
    void postersAreExtractedFromMaterialDataWithOriginalPriority() {
        var content = baseContent("movie");
        content.setMaterialData(
                "{"
                        + "\"poster_url_original\":\"https://kp/big.jpg\","
                        + "\"poster_url\":\"https://kp/small.jpg\""
                        + "}");
        var variants = List.of(playableVariant(11L, 0, 0, "TR", "https://cdn/11.mp4"));

        var event = SourceEventMapper.toEvent(content, variants, FIXED_CLOCK);

        var movie = (SourceCatalogEvent.MovieDiscovered) event;
        assertThat(movie.info().posterUrl()).isEqualTo("https://kp/big.jpg");
        assertThat(movie.info().bigPosterUrl()).isEqualTo("https://kp/big.jpg");
    }

    @Test
    void posterUrlFallsBackThroughKindSpecialisedFields() {
        var content = baseContent("anime-serial");
        content.setMaterialData(
                "{\"anime_poster_url\":\"https://kodik/anime.jpg\","
                        + "\"drama_poster_url\":\"https://kodik/drama.jpg\"}");
        var variants = List.of(playableVariant(50L, 1, 1, "TR", "https://cdn/50.mp4"));

        var event = SourceEventMapper.toEvent(content, variants, FIXED_CLOCK);

        var serial = (SourceCatalogEvent.SeriesDiscovered) event;
        assertThat(serial.info().posterUrl()).isEqualTo("https://kodik/anime.jpg");
        assertThat(serial.info().bigPosterUrl()).isNull();
    }

    @Test
    void screenshotUrlsArePopulatedFromScreenshotsJson() {
        var content = baseContent("movie");
        content.setScreenshots("[\"https://shot/1.jpg\",\"https://shot/2.jpg\",\"\",null]");
        var variants = List.of(playableVariant(11L, 0, 0, "TR", "https://cdn/11.mp4"));

        var event = SourceEventMapper.toEvent(content, variants, FIXED_CLOCK);

        var movie = (SourceCatalogEvent.MovieDiscovered) event;
        assertThat(movie.info().screenshotUrls())
                .containsExactly("https://shot/1.jpg", "https://shot/2.jpg");
    }

    @Test
    void postersAreEmptyWhenMaterialDataIsMissingOrUnparsable() {
        var content = baseContent("movie");
        content.setMaterialData("not-a-json");
        var variants = List.of(playableVariant(11L, 0, 0, "TR", "https://cdn/11.mp4"));

        var event = SourceEventMapper.toEvent(content, variants, FIXED_CLOCK);

        var movie = (SourceCatalogEvent.MovieDiscovered) event;
        assertThat(movie.info().posterUrl()).isNull();
        assertThat(movie.info().bigPosterUrl()).isNull();
        assertThat(movie.info().screenshotUrls()).isEmpty();
        assertThat(movie.info().trailerUrls()).isEmpty();
    }

    private static KodikContent baseContent(String type) {
        return KodikContent.builder()
                .id(100L)
                .kodikId("kodik-raw-100")
                .type(type)
                .title("Title RU")
                .titleOrig("Title EN")
                .year(2024)
                .build();
    }

    private static KodikEpisodeVariant playableVariant(
            Long id, int seasonNumber, int episodeNumber, String translationTitle, String mp4Link) {
        return KodikEpisodeVariant.builder()
                .id(id)
                .contentId(100L)
                .seasonNumber(seasonNumber)
                .episodeNumber(episodeNumber)
                .translationTitle(translationTitle)
                .mp4Link(mp4Link)
                .quality("HD")
                .build();
    }
}
