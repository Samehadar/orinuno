package com.orinuno.client;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.type.TypeReference;
import com.orinuno.client.dto.reference.KodikGenreDto;
import com.orinuno.client.dto.reference.KodikReferenceResponse;
import com.orinuno.client.dto.reference.KodikTranslationDto;
import com.orinuno.client.dto.reference.KodikYearDto;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("KodikResponseMapper — TypeReference overload and reference drift detection")
class KodikResponseMapperTest {

    private KodikResponseMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new KodikResponseMapper();
    }

    @Test
    @DisplayName("converts generic envelope via TypeReference")
    void convertsEnvelope() {
        Map<String, Object> raw =
                Map.of(
                        "time",
                        "1ms",
                        "total",
                        1,
                        "results",
                        List.of(Map.of("id", 610, "title", "JFK", "count", 5)));

        KodikReferenceResponse<KodikTranslationDto> env =
                mapper.mapAndDetectChanges(
                        raw,
                        new TypeReference<KodikReferenceResponse<KodikTranslationDto>>() {},
                        KodikTranslationDto.class);

        assertThat(env.getTotal()).isEqualTo(1);
        assertThat(env.getResults().get(0).id()).isEqualTo(610);
        assertThat(env.getResults().get(0).title()).isEqualTo("JFK");
        assertThat(env.getResults().get(0).count()).isEqualTo(5);
    }

    @Test
    @DisplayName("no drift reported on known envelope and item fields")
    void noDriftOnKnownFields() {
        Map<String, Object> raw =
                Map.of(
                        "time",
                        "1ms",
                        "total",
                        1,
                        "results",
                        List.of(Map.of("title", "Аниме", "count", 100)));

        mapper.mapAndDetectChanges(
                raw,
                new TypeReference<KodikReferenceResponse<KodikGenreDto>>() {},
                KodikGenreDto.class);

        assertThat(mapper.getDetectedDrifts()).isEmpty();
        assertThat(mapper.getTotalDriftsDetected().get()).isZero();
        assertThat(mapper.getTotalChecks().get()).isEqualTo(1);
    }

    @Test
    @DisplayName("detects drift on unknown envelope field")
    void detectsEnvelopeDrift() {
        Map<String, Object> raw =
                Map.of(
                        "time",
                        "1ms",
                        "total",
                        0,
                        "results",
                        List.of(),
                        "unexpected_envelope",
                        "new-thing");

        mapper.mapAndDetectChanges(
                raw,
                new TypeReference<KodikReferenceResponse<KodikGenreDto>>() {},
                KodikGenreDto.class);

        assertThat(mapper.getTotalDriftsDetected().get()).isEqualTo(1);
        assertThat(mapper.getDetectedDrifts()).containsKey("KodikReferenceResponse<KodikGenreDto>");
        assertThat(
                        mapper.getDetectedDrifts()
                                .get("KodikReferenceResponse<KodikGenreDto>")
                                .unknownFields())
                .containsExactly("unexpected_envelope");
    }

    @Test
    @DisplayName("detects drift on unknown item field")
    void detectsItemDrift() {
        Map<String, Object> raw =
                Map.of(
                        "time",
                        "1ms",
                        "total",
                        1,
                        "results",
                        List.of(Map.of("year", 2024, "count", 42, "new_metric", "added")));

        mapper.mapAndDetectChanges(
                raw,
                new TypeReference<KodikReferenceResponse<KodikYearDto>>() {},
                KodikYearDto.class);

        assertThat(mapper.getTotalDriftsDetected().get()).isEqualTo(1);
        assertThat(mapper.getDetectedDrifts()).containsKey("KodikYearDto");
        assertThat(mapper.getDetectedDrifts().get("KodikYearDto").unknownFields())
                .containsExactly("new_metric");
    }

    @Test
    @DisplayName("drift recorder increments hitCount on repeated drift")
    void driftHitCountAccumulates() {
        Map<String, Object> raw =
                Map.of(
                        "time",
                        "1ms",
                        "total",
                        1,
                        "results",
                        List.of(Map.of("title", "G", "count", 1, "foo", "bar")));

        mapper.mapAndDetectChanges(
                raw,
                new TypeReference<KodikReferenceResponse<KodikGenreDto>>() {},
                KodikGenreDto.class);
        mapper.mapAndDetectChanges(
                raw,
                new TypeReference<KodikReferenceResponse<KodikGenreDto>>() {},
                KodikGenreDto.class);

        assertThat(mapper.getDetectedDrifts().get("KodikGenreDto").hitCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("sampleMaterialData records drift under content-type-aware label")
    void samplesMaterialDataDrift() {
        Map<String, Object> raw =
                Map.of(
                        "time",
                        "1ms",
                        "total",
                        1,
                        "results",
                        List.of(
                                Map.of(
                                        "id",
                                        "a",
                                        "type",
                                        "anime-serial",
                                        "material_data",
                                        Map.of(
                                                "title",
                                                "Naruto",
                                                "anime_title",
                                                "Naruto",
                                                "unexpected_md_field",
                                                "v"))));

        mapper.detectSchemaChanges(raw, com.orinuno.client.dto.KodikSearchResponse.class);

        assertThat(mapper.getDetectedDrifts()).containsKey("MaterialData[anime-serial]");
        assertThat(mapper.getDetectedDrifts().get("MaterialData[anime-serial]").unknownFields())
                .contains("unexpected_md_field");
    }

    @Test
    @DisplayName("material_data label falls back to 'unknown' when type is absent")
    void samplesMaterialDataWithoutType() {
        Map<String, Object> raw =
                Map.of(
                        "time",
                        "1ms",
                        "total",
                        1,
                        "results",
                        List.of(
                                Map.of(
                                        "id",
                                        "a",
                                        "material_data",
                                        Map.of("title", "X", "rogue_md", "v"))));

        mapper.detectSchemaChanges(raw, com.orinuno.client.dto.KodikSearchResponse.class);

        assertThat(mapper.getDetectedDrifts()).containsKey("MaterialData[unknown]");
    }

    @Test
    @DisplayName("clean material_data keeps drift map empty")
    void cleanMaterialDataNoDrift() {
        Map<String, Object> raw =
                Map.of(
                        "time",
                        "1ms",
                        "total",
                        1,
                        "results",
                        List.of(
                                Map.of(
                                        "id",
                                        "a",
                                        "type",
                                        "anime",
                                        "material_data",
                                        Map.of("title", "X", "year", 2024))));

        mapper.detectSchemaChanges(raw, com.orinuno.client.dto.KodikSearchResponse.class);

        assertThat(mapper.getDetectedDrifts()).isEmpty();
    }
}
