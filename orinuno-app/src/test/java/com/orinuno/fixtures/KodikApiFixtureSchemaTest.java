package com.orinuno.fixtures;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.kodik.sdk.drift.DriftDetector;
import com.kodik.sdk.drift.DriftRecord;
import com.kodik.sdk.drift.DriftSamplingProperties;
import com.orinuno.client.dto.KodikSearchResponse;
import com.orinuno.client.dto.calendar.KodikCalendarAnimeDto;
import com.orinuno.client.dto.calendar.KodikCalendarEntryDto;
import com.orinuno.client.dto.calendar.KodikCalendarImageDto;
import com.orinuno.client.dto.reference.KodikCountryDto;
import com.orinuno.client.dto.reference.KodikGenreDto;
import com.orinuno.client.dto.reference.KodikQualityDto;
import com.orinuno.client.dto.reference.KodikReferenceResponse;
import com.orinuno.client.dto.reference.KodikTranslationDto;
import com.orinuno.client.dto.reference.KodikYearDto;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * DUMP-3 — Offline schema regression suite. Captures real-world Kodik response shapes (with the
 * token redacted) and pins them as committed test fixtures, so:
 *
 * <ul>
 *   <li>Every PR that touches a DTO is automatically re-run against the actual Kodik response shape
 *       — no need to re-discover the field set by hand.
 *   <li>The {@link DriftDetector} can be regression-tested without network access (the live drift
 *       tests need a token AND a CIS IP).
 *   <li>If Kodik adds a field upstream we won't notice immediately, but the moment we re-capture
 *       the fixture the drift detector flags it on the next test run.
 * </ul>
 *
 * <p>Fixture provenance: captured 2026-05-02 from a Kazakhstan IP using token {@code
 * 4492ae176f94d3103750b9443139fdc5} (now redacted in-repo). To refresh: see {@code
 * scripts/research/2026-05-02-api-and-decoder-probe.sh}.
 */
class KodikApiFixtureSchemaTest {

    private static final ObjectMapper OBJECT_MAPPER =
            new ObjectMapper().registerModule(new JavaTimeModule());
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    @Test
    @DisplayName(
            "fixtures/api/search-anime-serial.json deserialises into KodikSearchResponse cleanly")
    void searchAnimeSerialDeserialises() throws IOException {
        KodikSearchResponse resp =
                readFixture("api/search-anime-serial.json", KodikSearchResponse.class);

        assertThat(resp.getResults()).isNotEmpty();
        KodikSearchResponse.Result first = resp.getResults().get(0);
        assertThat(first.getId()).isNotBlank();
        assertThat(first.getType()).isEqualTo("anime-serial");
        assertThat(first.getLink()).startsWith("//kodikplayer.com");
        assertThat(first.getTranslation()).isNotNull();
        assertThat(first.getTranslation().getId()).isNotNull();
        assertThat(first.getMaterialData()).isNotEmpty();
    }

    @Test
    @DisplayName("fixtures/api/list-anime-serial.json deserialises with pagination cursor")
    void listAnimeSerialDeserialisesWithPagination() throws IOException {
        KodikSearchResponse resp =
                readFixture("api/list-anime-serial.json", KodikSearchResponse.class);

        assertThat(resp.getResults()).isNotEmpty();
        assertThat(resp.getNextPage())
                .as("Kodik /list always returns next_page when more results exist")
                .isNotBlank();
    }

    @Test
    @DisplayName("fixtures/api/list-foreign-movie.json deserialises kinopoisk_id and material_data")
    void listForeignMovieHasKinopoiskAndMaterialData() throws IOException {
        KodikSearchResponse resp =
                readFixture("api/list-foreign-movie.json", KodikSearchResponse.class);

        KodikSearchResponse.Result m = resp.getResults().get(0);
        assertThat(m.getType()).isEqualTo("foreign-movie");
        assertThat(m.getKinopoiskId()).isNotBlank();
        assertThat(m.getMaterialData()).containsKey("title");
        assertThat(m.getYear()).isPositive();
    }

    @Test
    @DisplayName("fixtures/api/translations-v2-anime.json deserialises into reference envelope")
    void translationsV2DeserialisesIntoReferenceEnvelope() throws IOException {
        KodikReferenceResponse<KodikTranslationDto> resp =
                readFixtureGeneric("api/translations-v2-anime.json", KodikTranslationDto.class);

        assertThat(resp.getResults()).isNotEmpty();
        KodikTranslationDto first = resp.getResults().get(0);
        assertThat(first.id()).isNotNull();
        assertThat(first.title()).isNotBlank();
    }

    @Test
    @DisplayName("fixtures/api/genres-anime.json deserialises into reference envelope")
    void genresAnimeDeserialisesIntoReferenceEnvelope() throws IOException {
        KodikReferenceResponse<KodikGenreDto> resp =
                readFixtureGeneric("api/genres-anime.json", KodikGenreDto.class);
        assertThat(resp.getResults()).isNotEmpty();
        assertThat(resp.getResults().get(0).title()).isNotBlank();
    }

    @Test
    @DisplayName("fixtures/api/qualities-v2.json deserialises into reference envelope")
    void qualitiesV2Deserialises() throws IOException {
        KodikReferenceResponse<KodikQualityDto> resp =
                readFixtureGeneric("api/qualities-v2.json", KodikQualityDto.class);
        assertThat(resp.getResults()).isNotEmpty();
        assertThat(resp.getResults().get(0).title()).isNotBlank();
    }

    @Test
    @DisplayName("fixtures/api/years.json deserialises into reference envelope")
    void yearsDeserialises() throws IOException {
        KodikReferenceResponse<KodikYearDto> resp =
                readFixtureGeneric("api/years.json", KodikYearDto.class);
        assertThat(resp.getResults()).isNotEmpty();
        assertThat(resp.getResults().get(0).year()).isPositive();
    }

    @Test
    @DisplayName("fixtures/api/countries.json deserialises into reference envelope")
    void countriesDeserialises() throws IOException {
        KodikReferenceResponse<KodikCountryDto> resp =
                readFixtureGeneric("api/countries.json", KodikCountryDto.class);
        assertThat(resp.getResults()).isNotEmpty();
        assertThat(resp.getResults().get(0).title()).isNotBlank();
    }

    @Test
    @DisplayName(
            "Drift detector finds zero unknown fields in search-anime-serial fixture (regression"
                    + " trip-wire — fails when Kodik adds new fields and we forget to update the"
                    + " DTO)")
    void driftDetectorFindsNoUnknownFieldsInSearchFixture() throws IOException {
        DriftDetector detector = new DriftDetector(driftSampling(50));

        Map<String, Object> raw = readFixtureRaw("api/search-anime-serial.json");
        detector.detectEnvelopeAndItems(
                raw, KodikSearchResponse.class, KodikSearchResponse.Result.class);

        Map<String, DriftRecord> drifts = detector.getDetectedDrifts();
        assertThat(drifts)
                .as(
                        "If Kodik added a field upstream and we re-captured the fixture, this will"
                                + " surface the drift names. Update the DTO, re-run.")
                .isEmpty();
    }

    @Test
    @DisplayName(
            "Drift detector finds zero unknown fields in list-anime-serial fixture (covers"
                    + " /list-specific envelope keys like prev_page / next_page)")
    void driftDetectorFindsNoUnknownFieldsInListFixture() throws IOException {
        DriftDetector detector = new DriftDetector(driftSampling(50));

        Map<String, Object> raw = readFixtureRaw("api/list-anime-serial.json");
        detector.detectEnvelopeAndItems(
                raw, KodikSearchResponse.class, KodikSearchResponse.Result.class);

        assertThat(detector.getDetectedDrifts()).isEmpty();
    }

    @Test
    @DisplayName(
            "fixtures/dumps/calendar-sample.json deserialises into KodikCalendarEntryDto list +"
                    + " no drift on entry / anime / image shapes")
    void calendarSampleDeserialisesAndDriftClean() throws IOException {
        List<KodikCalendarEntryDto> entries =
                readFixtureList("dumps/calendar-sample.json", KodikCalendarEntryDto.class);

        assertThat(entries).isNotEmpty();
        KodikCalendarEntryDto first = entries.get(0);
        assertThat(first.nextEpisode()).isNotNull();
        assertThat(first.anime()).isNotNull();

        DriftDetector detector = new DriftDetector(driftSampling(20));
        List<Map<String, Object>> raw = readFixtureRawList("dumps/calendar-sample.json");
        for (Map<String, Object> entryMap : raw) {
            detector.detect(entryMap, KodikCalendarEntryDto.class, "KodikCalendarEntryDto");
            Object animeRaw = entryMap.get("anime");
            if (animeRaw instanceof Map<?, ?> animeMap) {
                @SuppressWarnings("unchecked")
                Map<String, Object> coerced = (Map<String, Object>) animeMap;
                detector.detect(coerced, KodikCalendarAnimeDto.class, "KodikCalendarAnimeDto");
                Object imageRaw = coerced.get("image");
                if (imageRaw instanceof Map<?, ?> imageMap) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> coercedImage = (Map<String, Object>) imageMap;
                    detector.detect(
                            coercedImage, KodikCalendarImageDto.class, "KodikCalendarImageDto");
                }
            }
        }

        assertThat(detector.getDetectedDrifts())
                .as("Calendar dump shape regression trip-wire")
                .isEmpty();
    }

    // -- helpers --------------------------------------------------------------

    private <T> T readFixture(String name, Class<T> type) throws IOException {
        try (InputStream stream = openFixture(name)) {
            return OBJECT_MAPPER.readValue(stream, type);
        }
    }

    private Map<String, Object> readFixtureRaw(String name) throws IOException {
        try (InputStream stream = openFixture(name)) {
            return OBJECT_MAPPER.readValue(stream, MAP_TYPE);
        }
    }

    private List<Map<String, Object>> readFixtureRawList(String name) throws IOException {
        try (InputStream stream = openFixture(name)) {
            return OBJECT_MAPPER.readValue(
                    stream, new TypeReference<List<Map<String, Object>>>() {});
        }
    }

    private <T> List<T> readFixtureList(String name, Class<T> itemType) throws IOException {
        try (InputStream stream = openFixture(name)) {
            return OBJECT_MAPPER.readValue(
                    stream,
                    OBJECT_MAPPER.getTypeFactory().constructCollectionType(List.class, itemType));
        }
    }

    private <T> KodikReferenceResponse<T> readFixtureGeneric(String name, Class<T> itemType)
            throws IOException {
        try (InputStream stream = openFixture(name)) {
            return OBJECT_MAPPER.readValue(
                    stream,
                    OBJECT_MAPPER
                            .getTypeFactory()
                            .constructParametricType(KodikReferenceResponse.class, itemType));
        }
    }

    private static DriftSamplingProperties driftSampling(int limit) {
        DriftSamplingProperties props = new DriftSamplingProperties();
        props.setEnabled(true);
        DriftSamplingProperties.ItemSampling sampling = new DriftSamplingProperties.ItemSampling();
        sampling.setLimit(limit);
        props.setItemSampling(sampling);
        return props;
    }

    private InputStream openFixture(String name) {
        InputStream stream =
                KodikApiFixtureSchemaTest.class.getResourceAsStream(
                        "/com/orinuno/fixtures/" + name);
        if (stream == null) {
            throw new IllegalStateException("Fixture not found on classpath: " + name);
        }
        return stream;
    }
}
