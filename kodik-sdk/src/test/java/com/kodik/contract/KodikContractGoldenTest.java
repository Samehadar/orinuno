/*
 * KodikContractGoldenTest — ADR 0018 Phase 1.9.
 *
 * Locks the public wire-shape of the kodik-sdk DTOs. Every golden file in
 * src/test/resources/golden/ pins one piece of the surface:
 *
 *   search-response.json              Kodik /search response → KodikSearchResponse
 *   search-request.json               KodikSearchRequest serialization
 *   reference-genres-response.json    /genres → KodikReferenceResponse<KodikGenreDto>
 *   reference-translations-response.json
 *                                      /translations → KodikReferenceResponse<KodikTranslationDto>
 *   decode-attempt-result.json        DecodeAttemptResult JSON form
 *   token-config.json                 KodikTokenConfig record serialization
 *
 * Strategy: each test parses a golden file into the matching SDK POJO and
 * asserts the documented field set. Failure modes the goldens catch:
 *
 *   1. Field rename — golden's snake_case key no longer maps to the POJO
 *      field; the getter assertion returns null and the test fails.
 *   2. Type change — Jackson throws during parse (Integer → String regression
 *      etc.), test fails on the parse call.
 *   3. New required wire field — golden lacks the new key; if downstream code
 *      starts depending on it, that test adds a getter assertion and the
 *      golden grows in the same PR.
 *
 * If you intentionally evolve a DTO, refresh the golden file in the same PR;
 * the test is the conscious-bump gate, not a fossil.
 */
package com.kodik.contract;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kodik.client.dto.KodikSearchRequest;
import com.kodik.client.dto.KodikSearchResponse;
import com.kodik.client.dto.reference.KodikGenreDto;
import com.kodik.client.dto.reference.KodikReferenceResponse;
import com.kodik.client.dto.reference.KodikTranslationDto;
import com.kodik.decoder.DecodeAttemptResult;
import com.kodik.token.KodikTokenConfig;
import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("kodik-sdk contract — ADR 0018 Phase 1.9 golden surface")
class KodikContractGoldenTest {

    private static final ObjectMapper MAPPER =
            new ObjectMapper()
                    // DecodeAttemptResult is a record whose `isEmpty()` method
                    // serializes as `empty: false` but whose canonical constructor
                    // only knows `method` + `qualities`. The round-trip parse path
                    // needs FAIL_ON_UNKNOWN_PROPERTIES off so the synthetic
                    // `empty` doesn't crash deserialization.
                    .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    // ── /search ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("KodikSearchResponse parses the /search wire shape with snake_case fields intact")
    void searchResponseParse() throws Exception {
        JsonNode golden = loadGolden("search-response.json");
        KodikSearchResponse parsed = MAPPER.treeToValue(golden, KodikSearchResponse.class);

        assertThat(parsed.getTime()).isEqualTo("8ms");
        assertThat(parsed.getTotal()).isEqualTo(1);
        assertThat(parsed.getResults()).hasSize(1);

        KodikSearchResponse.Result row = parsed.getResults().get(0);
        // These getters cover the snake_case → camelCase @JsonProperty
        // bindings that downstream code (orinuno-source-kodik, downstream consumer)
        // depends on. A regression on any one of them breaks the wire form.
        assertThat(row.getId()).isEqualTo("serial-52242");
        assertThat(row.getType()).isEqualTo("anime-serial");
        assertThat(row.getTitle()).isEqualTo("Наруто [ТВ-1]");
        assertThat(row.getTitleOrig()).isEqualTo("Naruto");
        assertThat(row.getYear()).isEqualTo(2002);
        assertThat(row.getLastEpisode()).isEqualTo(220);
        assertThat(row.getLastSeason()).isEqualTo(1);
        assertThat(row.getEpisodesCount()).isEqualTo(220);
        assertThat(row.getKinopoiskId()).isEqualTo("283290");
        assertThat(row.getImdbId()).isEqualTo("tt0409591");
        assertThat(row.getShikimoriId()).isEqualTo("20");
        assertThat(row.getQuality()).isEqualTo("BDRip 720p");
        assertThat(row.getCamrip()).isFalse();
        assertThat(row.getCreatedAt()).isEqualTo("2018-09-01T12:00:00+00:00");
        assertThat(row.getUpdatedAt()).isEqualTo("2026-05-12T01:23:45+00:00");
        assertThat(row.getScreenshots()).hasSize(1);
        assertThat(row.getTranslation().getTitle()).isEqualTo("AniDUB");
        assertThat(row.getTranslation().getId()).isEqualTo(610);
    }

    @Test
    @DisplayName("KodikSearchRequest serialization emits the documented field names")
    void searchRequestSerialization() throws Exception {
        KodikSearchRequest request =
                KodikSearchRequest.builder()
                        .title("Naruto")
                        .limit(20)
                        .withEpisodes(true)
                        .withMaterialData(true)
                        .types("anime,anime-serial")
                        .build();

        JsonNode actual = MAPPER.valueToTree(request);
        JsonNode golden = loadGolden("search-request.json");

        // Each populated field must round-trip with the same key + value.
        // Unset boxed fields serialize to JSON null today; tests don't assert
        // their absence so a future @JsonInclude(NON_NULL) flip wouldn't
        // accidentally fail this test (regenerate the golden anyway).
        for (String field :
                new String[] {"title", "limit", "withEpisodes", "withMaterialData", "types"}) {
            assertThat(actual.get(field))
                    .as("KodikSearchRequest must emit field '%s' (golden contract)", field)
                    .isNotNull();
            assertThat(actual.get(field))
                    .as("KodikSearchRequest field '%s' must match golden value", field)
                    .isEqualTo(golden.get(field));
        }
    }

    // ── /reference/* ───────────────────────────────────────────────────────

    @Test
    @DisplayName("/genres response → KodikReferenceResponse<KodikGenreDto>")
    void genresResponseParse() throws Exception {
        JsonNode golden = loadGolden("reference-genres-response.json");
        KodikReferenceResponse<KodikGenreDto> parsed =
                MAPPER.readValue(
                        golden.toString(),
                        new TypeReference<KodikReferenceResponse<KodikGenreDto>>() {});

        assertThat(parsed.getTime()).isEqualTo("9ms");
        assertThat(parsed.getTotal()).isEqualTo(30);
        assertThat(parsed.getResults()).hasSize(3);

        KodikGenreDto first = parsed.getResults().get(0);
        assertThat(first.title()).isEqualTo("аниме");
        assertThat(first.count()).isEqualTo(24594);
        // KodikGenreDto is intentionally without an `id` — the Kodik genre
        // endpoint never returns one. Lock that contract by asserting the
        // record has exactly two components.
        assertThat(KodikGenreDto.class.getRecordComponents()).hasSize(2);
    }

    @Test
    @DisplayName("/translations response → KodikReferenceResponse<KodikTranslationDto>")
    void translationsResponseParse() throws Exception {
        JsonNode golden = loadGolden("reference-translations-response.json");
        KodikReferenceResponse<KodikTranslationDto> parsed =
                MAPPER.readValue(
                        golden.toString(),
                        new TypeReference<KodikReferenceResponse<KodikTranslationDto>>() {});

        assertThat(parsed.getResults()).hasSize(2);

        KodikTranslationDto first = parsed.getResults().get(0);
        assertThat(first.id()).isEqualTo(610);
        assertThat(first.title()).isEqualTo("AniDUB");
        assertThat(first.count()).isEqualTo(7250);
        // KodikTranslationDto carries an `id` (unlike KodikGenreDto).
        assertThat(KodikTranslationDto.class.getRecordComponents()).hasSize(3);
    }

    // ── Decoder + token config ─────────────────────────────────────────────

    @Test
    @DisplayName("DecodeAttemptResult — method enum + qualities map round-trip via JSON")
    void decodeAttemptResultRoundTrip() throws Exception {
        Map<String, String> qualities = new LinkedHashMap<>();
        qualities.put("360", "https://cloud.kodik-storage.com/video/abc/360.mp4");
        qualities.put("480", "https://cloud.kodik-storage.com/video/abc/480.mp4");
        qualities.put("720", "https://cloud.kodik-storage.com/video/abc/720.mp4");
        DecodeAttemptResult result = DecodeAttemptResult.regex(qualities);

        JsonNode golden = loadGolden("decode-attempt-result.json");
        JsonNode actual = MAPPER.valueToTree(result);

        // Lock only the wire-relevant subset — `method` + `qualities` map.
        // The record's `isEmpty()` derived getter serializes as `empty: false`
        // but is NOT a documented wire field; we tolerate its presence.
        assertThat(actual.get("method").asText()).isEqualTo(golden.get("method").asText());
        assertThat(actual.get("qualities").get("360").asText())
                .isEqualTo(golden.get("qualities").get("360").asText());
        assertThat(actual.get("qualities").get("480").asText())
                .isEqualTo(golden.get("qualities").get("480").asText());
        assertThat(actual.get("qualities").get("720").asText())
                .isEqualTo(golden.get("qualities").get("720").asText());

        // Round-trip — DecodeAttemptResult must be reconstructable from its own
        // serialized form so internal persistence (e.g. test fixtures) stays sane.
        DecodeAttemptResult reParsed = MAPPER.treeToValue(actual, DecodeAttemptResult.class);
        assertThat(reParsed.method()).isEqualTo(result.method());
        assertThat(reParsed.qualities()).containsExactlyEntriesOf(qualities);
    }

    @Test
    @DisplayName("KodikTokenConfig — seven token-subsystem knobs round-trip via JSON")
    void tokenConfigRoundTrip() throws Exception {
        KodikTokenConfig cfg =
                KodikTokenConfig.builder()
                        .tokenFile("./data/kodik_tokens.json")
                        .bootstrapToken(null)
                        .bootstrapFromEnv(true)
                        .autoDiscoveryEnabled(true)
                        .validateOnStartup(true)
                        .deadRevalidationIntervalMinutes(1440)
                        .tokenFailoverMaxAttempts(3)
                        .build();

        JsonNode actual = MAPPER.valueToTree(cfg);
        JsonNode golden = loadGolden("token-config.json");

        // Assert the seven knobs by name. Numeric Int vs Long node mismatch is
        // sidestepped by comparing via asLong / asText rather than direct node
        // equality — Jackson normalises both to the same primitive.
        assertThat(actual.get("tokenFile").asText()).isEqualTo(golden.get("tokenFile").asText());
        assertThat(actual.get("bootstrapToken").isNull())
                .as("bootstrapToken null must survive the round-trip")
                .isTrue();
        assertThat(actual.get("bootstrapFromEnv").asBoolean())
                .isEqualTo(golden.get("bootstrapFromEnv").asBoolean());
        assertThat(actual.get("autoDiscoveryEnabled").asBoolean())
                .isEqualTo(golden.get("autoDiscoveryEnabled").asBoolean());
        assertThat(actual.get("validateOnStartup").asBoolean())
                .isEqualTo(golden.get("validateOnStartup").asBoolean());
        assertThat(actual.get("deadRevalidationIntervalMinutes").asLong())
                .isEqualTo(golden.get("deadRevalidationIntervalMinutes").asLong());
        assertThat(actual.get("tokenFailoverMaxAttempts").asInt())
                .isEqualTo(golden.get("tokenFailoverMaxAttempts").asInt());
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private static JsonNode loadGolden(String name) throws IOException {
        try (InputStream is =
                KodikContractGoldenTest.class.getResourceAsStream("/golden/" + name)) {
            if (is == null) {
                throw new IllegalStateException("Missing golden resource: /golden/" + name);
            }
            return MAPPER.readTree(is);
        }
    }
}
