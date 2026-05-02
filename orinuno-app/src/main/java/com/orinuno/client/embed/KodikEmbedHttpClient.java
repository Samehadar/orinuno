package com.orinuno.client.embed;

import com.orinuno.client.KodikApiRateLimiter;
import com.orinuno.configuration.OrinunoProperties;
import com.orinuno.token.KodikFunction;
import com.orinuno.token.KodikTokenException;
import com.orinuno.token.KodikTokenRegistry;
import com.orinuno.token.KodikTokenValidator;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Mono;

/**
 * Low-level fetcher for Kodik's {@code GET /get-player} helper endpoint. {@code /get-player} is the
 * undocumented internal endpoint that powers Kodik's "embed by external id" widget — it accepts a
 * single external id ({@code shikimoriID}, {@code kinopoiskID}, …) and returns JSON with the
 * resolved iframe link.
 *
 * <p>Two responsibilities are delegated here, mirroring {@link
 * com.orinuno.client.calendar.KodikCalendarHttpClient}:
 *
 * <ol>
 *   <li>HTTP transport against the {@code kodikApiWebClient} bean (so {@code orinuno.kodik.api-url}
 *       still controls the upstream host and we get the same SSL/proxy stack as {@code /search}).
 *   <li>Token failover identical to {@link com.orinuno.client.KodikApiClient} — picks the next
 *       eligible token from {@link KodikTokenRegistry} when Kodik replies with the literal {@code
 *       "Отсутствует или неверный токен"} string.
 * </ol>
 *
 * <p>Rate limiting goes through the shared {@link KodikApiRateLimiter} so {@code /get-player}
 * counts against the same per-minute budget as {@code /search} and {@code /list} — this is
 * deliberately conservative: a misbehaving consumer cannot starve the parser pipeline by hammering
 * embed lookups.
 *
 * <p>Function tag is {@link KodikFunction#GET_INFO}: {@code /get-player} is essentially "look up
 * metadata by id" which is the closest semantic match in the token tier model. We do not introduce
 * a new {@code get_embed_link} function so the {@code data/kodik_tokens.json} layout stays
 * byte-compatible with AnimeParsers.
 */
@Slf4j
@Component
public class KodikEmbedHttpClient {

    private static final ParameterizedTypeReference<Map<String, Object>> MAP_TYPE =
            new ParameterizedTypeReference<>() {};

    private final WebClient kodikApiWebClient;
    private final OrinunoProperties properties;
    private final KodikTokenRegistry tokenRegistry;
    private final KodikApiRateLimiter rateLimiter;

    public KodikEmbedHttpClient(
            @Qualifier("kodikApiWebClient") WebClient kodikApiWebClient,
            OrinunoProperties properties,
            KodikTokenRegistry tokenRegistry,
            KodikApiRateLimiter rateLimiter) {
        this.kodikApiWebClient = kodikApiWebClient;
        this.properties = properties;
        this.tokenRegistry = tokenRegistry;
        this.rateLimiter = rateLimiter;
    }

    /**
     * Resolve the raw {@code /get-player} JSON for the supplied {@code (idType, normalizedId)}. The
     * caller is responsible for normalising the id (typically via {@link
     * KodikIdType#normalizeId(String)}) before invoking this method — this class deliberately does
     * not interpret the id beyond URL-encoding it.
     *
     * @return the deserialised JSON body. Common shapes:
     *     <ul>
     *       <li>{@code {"found": true, "link": "//kodikplayer.com/..."}} — happy path
     *       <li>{@code {"found": false, ...}} — id unknown to Kodik
     *       <li>{@code {"error": "..."}} — token rejected or other upstream issue
     *     </ul>
     */
    public Mono<Map<String, Object>> getPlayerRaw(KodikIdType idType, String normalizedId) {
        log.info("Kodik /get-player {}={}", idType.getKodikQueryKey(), maskId(normalizedId));
        return rateLimiter.wrapWithRateLimit(executeWithTokenFailover(idType, normalizedId, 0));
    }

    private Mono<Map<String, Object>> executeWithTokenFailover(
            KodikIdType idType, String normalizedId, int attempt) {
        String token;
        try {
            token = tokenRegistry.currentToken(KodikFunction.GET_INFO);
        } catch (KodikTokenException.NoWorkingTokenException ex) {
            return Mono.error(ex);
        }

        String findPlayerUrl = buildFindPlayerUrl(idType, normalizedId);

        return kodikApiWebClient
                .get()
                .uri(
                        uriBuilder ->
                                uriBuilder
                                        .path("/get-player")
                                        .queryParam("title", "Player")
                                        .queryParam("hasPlayer", "false")
                                        .queryParam("url", findPlayerUrl)
                                        .queryParam("token", token)
                                        .queryParam(idType.getKodikQueryKey(), normalizedId)
                                        .build())
                .retrieve()
                .bodyToMono(MAP_TYPE)
                .flatMap(
                        response -> {
                            Object error = response == null ? null : response.get("error");
                            if (error != null
                                    && KodikTokenValidator.INVALID_TOKEN_ERROR.equals(
                                            error.toString())) {
                                tokenRegistry.markInvalid(token, KodikFunction.GET_INFO);
                                int maxAttempts =
                                        Math.max(
                                                1,
                                                properties
                                                        .getKodik()
                                                        .getTokenFailoverMaxAttempts());
                                if (attempt + 1 >= maxAttempts) {
                                    return Mono.error(
                                            new KodikTokenException.TokenRejectedException(
                                                    "Kodik /get-player rejected every available"
                                                            + " token after "
                                                            + (attempt + 1)
                                                            + " attempt(s)"));
                                }
                                log.warn(
                                        "Kodik /get-player rejected token {} (attempt {})",
                                        KodikTokenRegistry.mask(token),
                                        attempt + 2);
                                return executeWithTokenFailover(idType, normalizedId, attempt + 1);
                            }
                            return Mono.just(response);
                        });
    }

    /**
     * Build the {@code url=} query parameter Kodik echoes back in the player iframe. We follow the
     * same {@code https://kodikdb.com/find-player?{key}={id}} shape AnimeParsers uses — it acts as
     * a referrer hint for Kodik's analytics and is not strictly required, but matching the
     * reference behaviour reduces the chance of upstream silently changing response shape on us.
     */
    private static String buildFindPlayerUrl(KodikIdType idType, String normalizedId) {
        return UriComponentsBuilder.fromUriString("https://kodikdb.com/find-player")
                .queryParam(idType.getKodikQueryKey(), normalizedId)
                .build()
                .encode(StandardCharsets.UTF_8)
                .toUriString();
    }

    private static String maskId(String id) {
        if (id == null || id.length() <= 4) {
            return "***";
        }
        return id.substring(0, 2) + "***" + id.substring(id.length() - 2);
    }
}
