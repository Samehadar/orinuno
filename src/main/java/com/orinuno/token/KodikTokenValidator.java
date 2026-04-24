package com.orinuno.token;

import com.orinuno.client.KodikResponseMapper;
import com.orinuno.client.dto.KodikSearchResponse;
import com.orinuno.configuration.OrinunoProperties;
import java.time.Duration;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * Probes Kodik API endpoints with a given token and updates the registry's per-function
 * availability matrix. Mirrors the spirit of AnimeParsers' {@code validate_token()}: a token is
 * either accepted or rejected at the API layer for each probed function.
 *
 * <p>Probes performed (pacing follows AnimeParsers' 2-second spacing between probes):
 *
 * <ul>
 *   <li>{@code POST /search} with {@code title} — covers {@link KodikFunction#BASE_SEARCH} / {@link
 *       KodikFunction#SEARCH}.
 *   <li>{@code POST /search} with {@code shikimori_id} — covers {@link
 *       KodikFunction#BASE_SEARCH_BY_ID} / {@link KodikFunction#SEARCH_BY_ID} / {@link
 *       KodikFunction#GET_INFO} / {@link KodikFunction#GET_LINK} / {@link
 *       KodikFunction#GET_M3U8_PLAYLIST_LINK}.
 *   <li>{@code POST /list} with {@code limit=1} — covers {@link KodikFunction#GET_LIST}.
 * </ul>
 */
@Slf4j
@Component
public class KodikTokenValidator {

    /** Exact phrase Kodik returns when a token is missing or rejected. */
    public static final String INVALID_TOKEN_ERROR = "Отсутствует или неверный токен";

    private static final String PROBE_TITLE = "Кулинарные скитания";
    private static final String PROBE_SHIKIMORI_ID = "53446";
    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(20);
    private static final Duration PROBE_SPACING = Duration.ofSeconds(2);

    private final WebClient kodikApiWebClient;
    private final OrinunoProperties properties;
    private final KodikTokenRegistry registry;
    private final KodikResponseMapper responseMapper;

    public KodikTokenValidator(
            @Qualifier("kodikApiWebClient") WebClient kodikApiWebClient,
            OrinunoProperties properties,
            KodikTokenRegistry registry,
            KodikResponseMapper responseMapper) {
        this.kodikApiWebClient = kodikApiWebClient;
        this.properties = properties;
        this.registry = registry;
        this.responseMapper = responseMapper;
    }

    /**
     * Probe a single token and update the registry. Returns the per-function result map actually
     * observed.
     */
    public Map<KodikFunction, Boolean> validate(String tokenValue) {
        EnumMap<KodikFunction, Boolean> results = new EnumMap<>(KodikFunction.class);

        boolean searchByTitle = probeSearchByTitle(tokenValue);
        results.put(KodikFunction.BASE_SEARCH, searchByTitle);
        results.put(KodikFunction.SEARCH, searchByTitle);
        sleepQuiet(PROBE_SPACING);

        boolean searchById = probeSearchByShikimoriId(tokenValue);
        results.put(KodikFunction.BASE_SEARCH_BY_ID, searchById);
        results.put(KodikFunction.SEARCH_BY_ID, searchById);
        results.put(KodikFunction.GET_INFO, searchById);
        results.put(KodikFunction.GET_LINK, searchById);
        results.put(KodikFunction.GET_M3U8_PLAYLIST_LINK, searchById);
        sleepQuiet(PROBE_SPACING);

        boolean list = probeList(tokenValue);
        results.put(KodikFunction.GET_LIST, list);

        applyResultsToRegistry(tokenValue, results);
        return results;
    }

    /**
     * Re-validate every live entry (stable / unstable / legacy) with pacing both within and between
     * tokens. Intended for the scheduled job.
     */
    public void validateAll() {
        List<KodikTokenEntry> live = new java.util.ArrayList<>();
        registry.snapshot()
                .forEach(
                        (tier, entries) -> {
                            if (tier == KodikTokenTier.DEAD) {
                                return;
                            }
                            live.addAll(entries);
                        });
        log.info("Revalidating {} Kodik token(s)", live.size());
        boolean first = true;
        for (KodikTokenEntry entry : live) {
            if (!first) {
                sleepQuiet(PROBE_SPACING);
            }
            first = false;
            try {
                validate(entry.getValue());
            } catch (RuntimeException ex) {
                log.warn(
                        "Validator: unexpected failure for token {}: {}",
                        KodikTokenRegistry.mask(entry.getValue()),
                        ex.getMessage());
            }
        }
    }

    // ======================== PROBES ========================

    private boolean probeSearchByTitle(String tokenValue) {
        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.add("token", tokenValue);
        params.add("title", PROBE_TITLE);
        params.add("limit", "1");
        return probe("/search", params, tokenValue, "search(title)");
    }

    private boolean probeSearchByShikimoriId(String tokenValue) {
        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.add("token", tokenValue);
        params.add("shikimori_id", PROBE_SHIKIMORI_ID);
        params.add("limit", "1");
        return probe("/search", params, tokenValue, "search(shikimori_id)");
    }

    private boolean probeList(String tokenValue) {
        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.add("token", tokenValue);
        params.add("limit", "1");
        return probe("/list", params, tokenValue, "list");
    }

    boolean probe(
            String path,
            MultiValueMap<String, String> params,
            String tokenValue,
            String probeName) {
        try {
            Map<String, Object> response =
                    kodikApiWebClient
                            .post()
                            .uri(uriBuilder -> uriBuilder.path(path).queryParams(params).build())
                            .retrieve()
                            .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                            .block(PROBE_TIMEOUT);
            if (response == null) {
                log.debug(
                        "Probe {} ({}) — empty body",
                        probeName,
                        KodikTokenRegistry.mask(tokenValue));
                return false;
            }
            Object error = response.get("error");
            if (error != null && INVALID_TOKEN_ERROR.equals(error.toString())) {
                log.debug(
                        "Probe {} ({}) rejected by Kodik: {}",
                        probeName,
                        KodikTokenRegistry.mask(tokenValue),
                        error);
                return false;
            }
            // Known coupling: probes share the same DriftDetector as runtime traffic, so their
            // sample bumps the detector's totalChecks/firstSeen alongside real /search and /list
            // calls. That's intentional today — probe payloads are real Kodik responses and any
            // drift visible to validators is also visible to users. If a future TrafficAnalyzer
            // splits probe vs. user traffic, route this call through a separate detector instance.
            responseMapper.detectSchemaChanges(response, KodikSearchResponse.class);
            return true;
        } catch (RuntimeException ex) {
            log.debug(
                    "Probe {} ({}) failed with {}: {}",
                    probeName,
                    KodikTokenRegistry.mask(tokenValue),
                    ex.getClass().getSimpleName(),
                    ex.getMessage());
            return false;
        }
    }

    private void applyResultsToRegistry(String tokenValue, Map<KodikFunction, Boolean> results) {
        results.forEach(
                (function, ok) -> {
                    if (Boolean.TRUE.equals(ok)) {
                        registry.markValid(tokenValue, function);
                    } else {
                        registry.markInvalid(tokenValue, function);
                    }
                });
    }

    private static void sleepQuiet(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    /** Exposed for tests. */
    Mono<Map<String, Object>> probeForTest(String path, MultiValueMap<String, String> params) {
        return kodikApiWebClient
                .post()
                .uri(uriBuilder -> uriBuilder.path(path).queryParams(params).build())
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {});
    }

    OrinunoProperties getProperties() {
        return properties;
    }
}
