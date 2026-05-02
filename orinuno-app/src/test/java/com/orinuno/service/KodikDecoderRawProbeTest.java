package com.orinuno.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Live re-run of the decoder body-format probe and quality-distribution probe described in
 * docs/research/2026-05-02-api-and-decoder-probe.md.
 *
 * <p>Backs ADR 0003 (form-encoded body required) and ADR 0004 (quality strategy).
 *
 * <p><strong>Geo requirement</strong>: must be executed from a CIS IP (KZ, RU, BY, KG). From other
 * regions Kodik returns dummy CDN URLs even when the API answers 200 — see docs/quirks-and-hacks.md
 * "Kodik is geo-fenced" entry.
 */
@EnabledIfEnvironmentVariable(named = "KODIK_TOKEN", matches = ".+")
@DisplayName("Kodik decoder raw probe (form-body + quality)")
class KodikDecoderRawProbeTest {

    private static final String API_BASE = "https://kodik-api.com";
    private static final String PLAYER_BASE = "https://kodikplayer.com";
    private static final String UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/135.0.0.0 Safari/537.36";

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
    private static final HttpClient HTTP =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();

    @org.junit.jupiter.api.Test
    @DisplayName("/ftor router rejects JSON Content-Type at routing layer with 404 (ADR 0003)")
    void ftorRejectsJsonBody() throws Exception {
        HttpResponse<String> resp =
                HTTP.send(
                        HttpRequest.newBuilder()
                                .uri(URI.create(PLAYER_BASE + "/ftor"))
                                .header("Content-Type", "application/json")
                                .header("User-Agent", UA)
                                .POST(
                                        HttpRequest.BodyPublishers.ofString(
                                                "{\"type\":\"video\",\"hash\":\"x\",\"id\":1,"
                                                    + "\"bad_user\":false,\"cdn_is_working\":true,\"info\":{}}"))
                                .timeout(Duration.ofSeconds(20))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());

        assertThat(resp.statusCode())
                .as(
                        "Kodik's router rejects non-form Content-Type at the entry point, never"
                                + " reaching app code — see ADR 0003. If this ever returns 200,"
                                + " Kodik may have started accepting JSON; revisit the ADR.")
                .isIn(404, 415);
    }

    @org.junit.jupiter.api.Test
    @DisplayName("/ftor app-layer returns 500 on form body with fake signed params (ADR 0003)")
    void ftorAppRejectsFormBodyWithFakeSignedParams() throws Exception {
        HttpResponse<String> resp =
                HTTP.send(
                        HttpRequest.newBuilder()
                                .uri(URI.create(PLAYER_BASE + "/ftor"))
                                .header("Content-Type", "application/x-www-form-urlencoded")
                                .header("User-Agent", UA)
                                .POST(
                                        HttpRequest.BodyPublishers.ofString(
                                                "type=video&hash=00000000000000000000000000000000"
                                                        + "&id=1&bad_user=false&cdn_is_working=true"
                                                        + "&info=%7B%7D"))
                                .timeout(Duration.ofSeconds(20))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());

        assertThat(resp.statusCode())
                .as(
                        "Form Content-Type passes the router; missing/fake signed params are"
                                + " app-rejected with 500 — see ADR 0003.")
                .isEqualTo(500);
    }

    @ParameterizedTest(name = "quality probe — {0} → expect 720p ceiling, default=360 (ADR 0004)")
    @CsvSource({
        "Naruto-shikimori,             20",
        "FMA Brotherhood-shikimori,   5114",
        "Attack on Titan-shikimori,  16498",
        "One Piece-shikimori,           21",
        "Steins Gate-shikimori,       9253",
    })
    void qualityDistributionMatchesAdr0004Snapshot(String label, String shikimoriId)
            throws Exception {
        String token = System.getenv("KODIK_TOKEN");

        Map<String, Object> searchResp =
                getJson(
                        API_BASE
                                + "/search?token="
                                + token
                                + "&shikimori_id="
                                + shikimoriId
                                + "&limit=1");
        Object resultsObj = searchResp.get("results");
        assertThat(resultsObj).as("/search must return results array").isInstanceOf(List.class);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> results = (List<Map<String, Object>>) resultsObj;
        assertThat(results).as("/search must return at least 1 result").isNotEmpty();
        String iframeLink = (String) results.get(0).get("link");
        assertThat(iframeLink).startsWith("//kodikplayer.com");

        String iframeUrl = "https:" + iframeLink;
        Map<String, String> ftorParams = extractIframeParams(iframeUrl);
        Map<String, Object> ftorResp = postFtor(ftorParams);

        Object linksObj = ftorResp.get("links");
        assertThat(linksObj).as("/ftor must return a 'links' map").isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> links = new TreeMap<>((Map<String, Object>) linksObj);

        // ADR 0004 snapshot guard: warn (don't fail) when 1080/2160 appears so we know to revisit.
        boolean has1080OrUp =
                links.keySet().stream()
                        .anyMatch(
                                q -> {
                                    try {
                                        return Integer.parseInt(q) >= 1080;
                                    } catch (NumberFormatException ignored) {
                                        return false;
                                    }
                                });
        if (has1080OrUp) {
            System.err.println(
                    "[ADR 0004 SNAPSHOT VIOLATED] " + label + " now has 1080+: " + links.keySet());
        }

        assertThat(links)
                .as("%s should expose at least the 360/480/720 trio", label)
                .containsKey("360");
        assertThat(links).containsKey("480");
        assertThat(links).containsKey("720");

        Object def = ftorResp.get("default");
        assertThat(def)
                .as("'default' field is informational; ADR 0004 says we ignore it")
                .isNotNull();
    }

    private static Map<String, Object> getJson(String url) throws Exception {
        HttpResponse<String> resp =
                HTTP.send(
                        HttpRequest.newBuilder()
                                .uri(URI.create(url))
                                .header("User-Agent", UA)
                                .GET()
                                .timeout(Duration.ofSeconds(20))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
        assertThat(resp.statusCode()).as("GET %s status", url).isEqualTo(200);
        return MAPPER.readValue(resp.body(), MAP_TYPE);
    }

    private static Map<String, String> extractIframeParams(String iframeUrl) throws Exception {
        HttpResponse<String> resp =
                HTTP.send(
                        HttpRequest.newBuilder()
                                .uri(URI.create(iframeUrl))
                                .header("User-Agent", UA)
                                .GET()
                                .timeout(Duration.ofSeconds(20))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
        assertThat(resp.statusCode()).isEqualTo(200);

        String html = resp.body();
        Map<String, String> params = new TreeMap<>();
        params.put("type", extract(html, "vInfo\\.type = '([^']+)'"));
        params.put("hash", extract(html, "vInfo\\.hash = '([^']+)'"));
        params.put("id", extract(html, "vInfo\\.id = '([^']+)'"));
        params.put("urlParams", extract(html, "var urlParams = '([^']*)'"));
        return params;
    }

    private static Map<String, Object> postFtor(Map<String, String> params) throws Exception {
        StringBuilder body = new StringBuilder();
        String urlParamsJson = params.getOrDefault("urlParams", "");
        if (!urlParamsJson.isBlank()) {
            Map<String, Object> urlParams = MAPPER.readValue(urlParamsJson, MAP_TYPE);
            for (Map.Entry<String, Object> e : urlParams.entrySet()) {
                appendParam(
                        body,
                        e.getKey(),
                        e.getValue() instanceof Boolean
                                ? Boolean.toString((Boolean) e.getValue()).toLowerCase()
                                : String.valueOf(e.getValue()));
            }
        }
        appendParam(body, "bad_user", "false");
        appendParam(body, "cdn_is_working", "true");
        appendParam(body, "type", params.get("type"));
        appendParam(body, "hash", params.get("hash"));
        appendParam(body, "id", params.get("id"));
        appendParam(body, "info", "{}");

        HttpResponse<String> resp =
                HTTP.send(
                        HttpRequest.newBuilder()
                                .uri(URI.create(PLAYER_BASE + "/ftor"))
                                .header("Content-Type", "application/x-www-form-urlencoded")
                                .header("X-Requested-With", "XMLHttpRequest")
                                .header("User-Agent", UA)
                                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                                .timeout(Duration.ofSeconds(20))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
        assertThat(resp.statusCode()).as("/ftor POST status").isEqualTo(200);
        return MAPPER.readValue(resp.body(), MAP_TYPE);
    }

    private static String extract(String html, String regex) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(regex).matcher(html);
        if (m.find()) return m.group(1);
        return null;
    }

    private static void appendParam(StringBuilder body, String key, String value) {
        if (value == null) return;
        if (body.length() > 0) body.append("&");
        body.append(java.net.URLEncoder.encode(key, java.nio.charset.StandardCharsets.UTF_8));
        body.append("=");
        body.append(java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8));
    }
}
