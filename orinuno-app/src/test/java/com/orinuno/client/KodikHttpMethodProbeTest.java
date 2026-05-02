package com.orinuno.client;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Live re-run of the GET-vs-POST probe described in
 * docs/research/2026-05-02-api-and-decoder-probe.md.
 *
 * <p>Backs ADR 0002. Fails the build if Kodik ever introduces method asymmetry on a public
 * endpoint, alerting us to revisit the ADR.
 */
@EnabledIfEnvironmentVariable(named = "KODIK_TOKEN", matches = ".+")
@DisplayName("Kodik HTTP method probe (GET vs POST)")
class KodikHttpMethodProbeTest {

    private static final String BASE_URL = "https://kodik-api.com";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
    private static final HttpClient HTTP =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();

    @ParameterizedTest(name = "{0} returns 200 on both GET and POST with byte-identical bodies")
    @CsvSource({
        "/list, limit=1",
        "/translations/v2,",
        "/genres,",
        "/countries,",
        "/years,",
        "/qualities/v2,",
        "/search, title=naruto&limit=1",
    })
    void getAndPostBehaveIdentically(String path, String filterQuery) throws Exception {
        String token = System.getenv("KODIK_TOKEN");
        String query =
                "token="
                        + token
                        + (filterQuery == null || filterQuery.isBlank() ? "" : "&" + filterQuery);

        HttpResponse<String> getResponse =
                HTTP.send(
                        HttpRequest.newBuilder()
                                .uri(URI.create(BASE_URL + path + "?" + query))
                                .GET()
                                .timeout(Duration.ofSeconds(30))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());

        HttpResponse<String> postResponse =
                HTTP.send(
                        HttpRequest.newBuilder()
                                .uri(URI.create(BASE_URL + path + "?" + query))
                                .POST(HttpRequest.BodyPublishers.noBody())
                                .timeout(Duration.ofSeconds(30))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());

        assertThat(getResponse.statusCode())
                .as("%s GET status (see ADR 0002 — both methods must work)", path)
                .isEqualTo(200);
        assertThat(postResponse.statusCode())
                .as("%s POST status (see ADR 0002 — both methods must work)", path)
                .isEqualTo(200);

        Map<String, Object> getBody = stripTime(MAPPER.readValue(getResponse.body(), MAP_TYPE));
        Map<String, Object> postBody = stripTime(MAPPER.readValue(postResponse.body(), MAP_TYPE));

        assertThat(getBody)
                .as(
                        "%s body must match modulo \"time\" field (see ADR 0002 — if mismatch,"
                                + " Kodik introduced method asymmetry)",
                        path)
                .isEqualTo(postBody);
    }

    @org.junit.jupiter.api.Test
    @DisplayName("/search without filter returns 500 + Russian error message (Kodik quirk)")
    void searchWithoutFilterReturnsKodik500() throws Exception {
        String token = System.getenv("KODIK_TOKEN");
        HttpResponse<String> resp =
                HTTP.send(
                        HttpRequest.newBuilder()
                                .uri(URI.create(BASE_URL + "/search?token=" + token))
                                .POST(HttpRequest.BodyPublishers.noBody())
                                .timeout(Duration.ofSeconds(15))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());

        assertThat(resp.statusCode())
                .as("Kodik uses HTTP 500 for application-level errors — see quirks-and-hacks.md")
                .isEqualTo(500);
        assertThat(resp.body())
                .as("Body should contain the Russian 'missing parameter' message")
                .contains("Не указан хотя бы один параметр для поиска");
    }

    @org.junit.jupiter.api.Test
    @DisplayName(
            "/search with bogus types= returns 500 + Russian 'wrong type' message (enum"
                    + " strictness)")
    void searchWithUnknownTypeReturnsKodik500() throws Exception {
        String token = System.getenv("KODIK_TOKEN");
        HttpResponse<String> resp =
                HTTP.send(
                        HttpRequest.newBuilder()
                                .uri(
                                        URI.create(
                                                BASE_URL
                                                        + "/search?token="
                                                        + token
                                                        + "&types=film&limit=1"))
                                .POST(HttpRequest.BodyPublishers.noBody())
                                .timeout(Duration.ofSeconds(15))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());

        assertThat(resp.statusCode()).isEqualTo(500);
        assertThat(resp.body()).contains("Неправильный тип");
    }

    private static Map<String, Object> stripTime(Map<String, Object> body) {
        body.remove("time");
        return body;
    }
}
