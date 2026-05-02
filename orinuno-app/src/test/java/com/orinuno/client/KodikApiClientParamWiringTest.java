package com.orinuno.client;

import static org.assertj.core.api.Assertions.assertThat;

import com.orinuno.client.dto.KodikListRequest;
import com.orinuno.client.dto.KodikReferenceRequest;
import com.orinuno.client.dto.KodikSearchRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.util.MultiValueMap;

/**
 * API-4 wire-up tests: verify {@link KodikApiClient}'s param-building helpers serialise every DTO
 * field into the form-encoded body (or, for /list pagination, into the absolute URL routing
 * branch).
 *
 * <p>Tests target the package-private static {@code buildSearchParams} / {@code buildListParams} /
 * {@code buildReferenceParams} helpers directly. Round-tripping through WebClient was prohibitively
 * fiddly in tests because Spring's WebClient form-data writer doesn't expose the materialised body
 * to {@link org.springframework.web.reactive.function.client.ExchangeFunction} hooks without a
 * custom {@link org.springframework.http.client.reactive.ClientHttpConnector}. Direct
 * helper-testing keeps the wire-format contract explicit and the test cheap.
 */
@DisplayName("KodikApiClient — API-4 param wiring")
class KodikApiClientParamWiringTest {

    @Test
    @DisplayName("buildSearchParams wires with_episodes_data when DTO sets it")
    void searchWiresWithEpisodesData() {
        MultiValueMap<String, String> p =
                KodikApiClient.buildSearchParams(
                        KodikSearchRequest.builder().withEpisodesData(true).title("any").build());
        assertThat(p.getFirst("with_episodes_data")).isEqualTo("true");
    }

    @Test
    @DisplayName(
            "buildSearchParams respects DTO override for with_seasons (was hardcoded 'true' before"
                    + " API-4)")
    void searchRespectsDtoOverride() {
        MultiValueMap<String, String> p =
                KodikApiClient.buildSearchParams(
                        KodikSearchRequest.builder().withSeasons(false).title("any").build());

        assertThat(p.get("with_seasons"))
                .as(
                        "DTO override must replace, not double, the param (regression of"
                                + " duplicate-key bug)")
                .containsExactly("false");
    }

    @Test
    @DisplayName("buildSearchParams keeps default 'true' when DTO leaves with_seasons null")
    void searchUsesDefaultWhenNull() {
        MultiValueMap<String, String> p =
                KodikApiClient.buildSearchParams(KodikSearchRequest.builder().title("any").build());

        assertThat(p.getFirst("with_seasons")).isEqualTo("true");
        assertThat(p.getFirst("with_episodes")).isEqualTo("true");
        assertThat(p.getFirst("with_episodes_data")).isEqualTo("false");
        assertThat(p.getFirst("with_material_data")).isEqualTo("true");
    }

    @Test
    @DisplayName("buildListParams uses DTO with_material_data override when set")
    void listRespectsDtoOverrideForWithMaterialData() {
        MultiValueMap<String, String> p =
                KodikApiClient.buildListParams(
                        KodikListRequest.builder().withMaterialData(false).build());
        assertThat(p.get("with_material_data")).containsExactly("false");
    }

    @Test
    @DisplayName(
            "buildReferenceParams emits genres_type only when allowGenresType=true (per-endpoint"
                    + " filtering)")
    void referenceParamsScopesGenresTypeToGenres() {
        KodikReferenceRequest req =
                KodikReferenceRequest.builder().genresType("anime").types("anime-serial").build();

        MultiValueMap<String, String> forGenres =
                KodikApiClient.buildReferenceParams(req, /* allowGenresType= */ true);
        assertThat(forGenres.getFirst("genres_type")).isEqualTo("anime");
        assertThat(forGenres.getFirst("types")).isEqualTo("anime-serial");

        MultiValueMap<String, String> forOthers =
                KodikApiClient.buildReferenceParams(req, /* allowGenresType= */ false);
        assertThat(forOthers.getFirst("genres_type"))
                .as("genres_type must NOT leak into endpoints that don't accept it")
                .isNull();
        assertThat(forOthers.getFirst("types")).isEqualTo("anime-serial");
    }

    @Test
    @DisplayName("buildReferenceParams returns an empty body when request is null (back-compat)")
    void referenceParamsHandlesNullRequest() {
        MultiValueMap<String, String> p =
                KodikApiClient.buildReferenceParams(null, /* allowGenresType= */ true);
        assertThat(p).isEmpty();
    }

    @Test
    @DisplayName(
            "buildReferenceParams skips blank values without sending them as empty form params")
    void referenceParamsSkipsBlankValues() {
        MultiValueMap<String, String> p =
                KodikApiClient.buildReferenceParams(
                        KodikReferenceRequest.builder().genresType("").types("   ").build(),
                        /* allowGenresType= */ true);
        assertThat(p)
                .as("blank/whitespace values trigger Kodik's 'Неправильный тип' 500 — must skip")
                .isEmpty();
    }
}
