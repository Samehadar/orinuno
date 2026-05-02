package com.orinuno.client.dto.reference;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Kodik reference DTO deserialization")
class KodikReferenceResponseDeserializationTest {

    private ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper();
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        mapper.setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
    }

    @Test
    @DisplayName("translations envelope: id/title/count populated")
    void translationsEnvelope() throws Exception {
        String json =
                """
                {
                  "time": "12ms",
                  "total": 2,
                  "results": [
                    {"id": 610, "title": "AniLibria.TV", "count": 1200},
                    {"id": 609, "title": "SovetRomantica", "count": 540}
                  ]
                }
                """;

        KodikReferenceResponse<KodikTranslationDto> env =
                mapper.readValue(
                        json, new TypeReference<KodikReferenceResponse<KodikTranslationDto>>() {});

        assertThat(env.getTime()).isEqualTo("12ms");
        assertThat(env.getTotal()).isEqualTo(2);
        assertThat(env.getResults())
                .extracting(KodikTranslationDto::id, KodikTranslationDto::title)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(610, "AniLibria.TV"),
                        org.assertj.core.groups.Tuple.tuple(609, "SovetRomantica"));
        assertThat(env.getResults().get(0).count()).isEqualTo(1200);
    }

    @Test
    @DisplayName("genres envelope: item has only title/count, no id")
    void genresEnvelope() throws Exception {
        String json =
                """
                {
                  "time": "3ms",
                  "total": 1,
                  "results": [ {"title": "Аниме", "count": 8800} ]
                }
                """;

        KodikReferenceResponse<KodikGenreDto> env =
                mapper.readValue(
                        json, new TypeReference<KodikReferenceResponse<KodikGenreDto>>() {});

        assertThat(env.getResults()).hasSize(1);
        KodikGenreDto genre = env.getResults().get(0);
        assertThat(genre.title()).isEqualTo("Аниме");
        assertThat(genre.count()).isEqualTo(8800);
    }

    @Test
    @DisplayName("countries envelope: item has only title/count, no id")
    void countriesEnvelope() throws Exception {
        String json =
                """
                {
                  "time": "2ms",
                  "total": 1,
                  "results": [ {"title": "Япония", "count": 3300} ]
                }
                """;

        KodikReferenceResponse<KodikCountryDto> env =
                mapper.readValue(
                        json, new TypeReference<KodikReferenceResponse<KodikCountryDto>>() {});

        assertThat(env.getResults().get(0).title()).isEqualTo("Япония");
        assertThat(env.getResults().get(0).count()).isEqualTo(3300);
    }

    @Test
    @DisplayName("years envelope: item has year (integer), not title")
    void yearsEnvelope() throws Exception {
        String json =
                """
                {
                  "time": "2ms",
                  "total": 2,
                  "results": [
                    {"year": 2023, "count": 720},
                    {"year": 2022, "count": 890}
                  ]
                }
                """;

        KodikReferenceResponse<KodikYearDto> env =
                mapper.readValue(
                        json, new TypeReference<KodikReferenceResponse<KodikYearDto>>() {});

        assertThat(env.getResults())
                .extracting(KodikYearDto::year, KodikYearDto::count)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(2023, 720),
                        org.assertj.core.groups.Tuple.tuple(2022, 890));
    }

    @Test
    @DisplayName("qualities envelope: item has title/count")
    void qualitiesEnvelope() throws Exception {
        String json =
                """
                {
                  "time": "1ms",
                  "total": 2,
                  "results": [
                    {"title": "HD 720", "count": 5000},
                    {"title": "Full HD 1080", "count": 1200}
                  ]
                }
                """;

        KodikReferenceResponse<KodikQualityDto> env =
                mapper.readValue(
                        json, new TypeReference<KodikReferenceResponse<KodikQualityDto>>() {});

        assertThat(env.getResults())
                .extracting(KodikQualityDto::title)
                .containsExactly("HD 720", "Full HD 1080");
    }

    @Test
    @DisplayName("tolerates unknown envelope fields (forwards-compat for Kodik API)")
    void unknownEnvelopeField() throws Exception {
        String json =
                """
                {
                  "time": "5ms",
                  "total": 0,
                  "results": [],
                  "unexpected_field": "value"
                }
                """;

        KodikReferenceResponse<KodikGenreDto> env =
                mapper.readValue(
                        json, new TypeReference<KodikReferenceResponse<KodikGenreDto>>() {});

        assertThat(env.getTime()).isEqualTo("5ms");
        assertThat(env.getResults()).isEmpty();
    }

    @Test
    @DisplayName("tolerates unknown item fields (forwards-compat for Kodik API)")
    void unknownItemField() throws Exception {
        String json =
                """
                {
                  "time": "1ms",
                  "total": 1,
                  "results": [ {"title": "Аниме", "count": 100, "foo": "bar"} ]
                }
                """;

        KodikReferenceResponse<KodikGenreDto> env =
                mapper.readValue(
                        json, new TypeReference<KodikReferenceResponse<KodikGenreDto>>() {});

        assertThat(env.getResults().get(0).title()).isEqualTo("Аниме");
    }
}
