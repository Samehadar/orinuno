package com.orinuno.client;

import com.fasterxml.jackson.core.type.TypeReference;
import com.orinuno.client.dto.KodikListRequest;
import com.orinuno.client.dto.KodikReferenceRequest;
import com.orinuno.client.dto.KodikSearchRequest;
import com.orinuno.client.dto.KodikSearchResponse;
import com.orinuno.client.dto.reference.KodikCountryDto;
import com.orinuno.client.dto.reference.KodikGenreDto;
import com.orinuno.client.dto.reference.KodikQualityDto;
import com.orinuno.client.dto.reference.KodikReferenceResponse;
import com.orinuno.client.dto.reference.KodikTranslationDto;
import com.orinuno.client.dto.reference.KodikYearDto;
import com.orinuno.configuration.OrinunoProperties;
import com.orinuno.token.KodikFunction;
import com.orinuno.token.KodikTokenException;
import com.orinuno.token.KodikTokenRegistry;
import com.orinuno.token.KodikTokenValidator;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Slf4j
@Component
public class KodikApiClient {

    private final WebClient kodikApiWebClient;
    private final OrinunoProperties properties;
    private final KodikResponseMapper responseMapper;
    private final KodikApiRateLimiter rateLimiter;
    private final KodikTokenRegistry tokenRegistry;

    public KodikApiClient(
            @Qualifier("kodikApiWebClient") WebClient kodikApiWebClient,
            OrinunoProperties properties,
            KodikResponseMapper responseMapper,
            KodikApiRateLimiter rateLimiter,
            KodikTokenRegistry tokenRegistry) {
        this.kodikApiWebClient = kodikApiWebClient;
        this.properties = properties;
        this.responseMapper = responseMapper;
        this.rateLimiter = rateLimiter;
        this.tokenRegistry = tokenRegistry;
    }

    // ======================== SEARCH ========================

    public Mono<Map<String, Object>> searchRaw(KodikSearchRequest request) {
        MultiValueMap<String, String> params = buildSearchParams(request);
        log.info("Kodik API /search with {} params", params.size());
        return rateLimiter.wrapWithRateLimit(postForMap("/search", params, pickFunction(request)));
    }

    public Mono<KodikSearchResponse> search(KodikSearchRequest request) {
        return searchRaw(request)
                .map(raw -> responseMapper.mapAndDetectChanges(raw, KodikSearchResponse.class))
                .doOnSuccess(
                        r ->
                                log.info(
                                        "Kodik /search returned {} results",
                                        r.getTotal() != null ? r.getTotal() : 0))
                .doOnError(e -> log.error("Kodik /search failed: {}", e.getMessage()));
    }

    // ======================== LIST ========================

    public Mono<Map<String, Object>> listRaw(KodikListRequest request) {
        Mono<Map<String, Object>> response;
        if (request.getNextPageUrl() != null && !request.getNextPageUrl().isBlank()) {
            log.info("Kodik API /list next page");
            response =
                    rateLimiter.wrapWithRateLimit(postForMapAbsoluteUrl(request.getNextPageUrl()));
        } else if (request.getPrevPageUrl() != null && !request.getPrevPageUrl().isBlank()) {
            log.info("Kodik API /list prev page");
            response =
                    rateLimiter.wrapWithRateLimit(postForMapAbsoluteUrl(request.getPrevPageUrl()));
        } else {
            MultiValueMap<String, String> params = buildListParams(request);
            log.info("Kodik API /list with {} params", params.size());
            response =
                    rateLimiter.wrapWithRateLimit(
                            postForMap("/list", params, KodikFunction.GET_LIST));
        }
        return response.doOnNext(
                raw -> responseMapper.detectSchemaChanges(raw, KodikSearchResponse.class));
    }

    /**
     * Auto-paginating wrapper over {@link #listRaw(KodikListRequest)} that walks {@code next_page}
     * until Kodik stops returning it, emitting each individual result item.
     *
     * <p>Internal utility for discovery / bulk sync scenarios — not exposed as a REST endpoint,
     * because iterating the full catalog synchronously would hold an HTTP connection open for
     * hours. Revisit once async jobs (TD-1) are available.
     */
    public Flux<Map<String, Object>> listAll(KodikListRequest request) {
        return listRaw(request)
                .expand(
                        raw -> {
                            Object next = raw.get("next_page");
                            if (!(next instanceof String s) || s.isBlank()) {
                                return Mono.empty();
                            }
                            return listRaw(KodikListRequest.builder().nextPageUrl(s).build());
                        })
                .flatMapIterable(
                        raw -> {
                            Object results = raw.get("results");
                            if (!(results instanceof List<?> list)) {
                                return List.of();
                            }
                            return list.stream()
                                    .filter(Map.class::isInstance)
                                    .map(item -> (Map<String, Object>) item)
                                    .toList();
                        });
    }

    // ======================== REFERENCE ENDPOINTS ========================

    public Mono<Map<String, Object>> translationsRaw() {
        return translationsRaw(null);
    }

    public Mono<Map<String, Object>> translationsRaw(KodikReferenceRequest request) {
        log.info("Kodik API /translations/v2");
        return rateLimiter.wrapWithRateLimit(
                postForMap(
                        "/translations/v2",
                        buildReferenceParams(request, /* allowGenresType= */ false),
                        KodikFunction.GET_INFO));
    }

    public Mono<Map<String, Object>> genresRaw() {
        return genresRaw(null);
    }

    public Mono<Map<String, Object>> genresRaw(KodikReferenceRequest request) {
        log.info("Kodik API /genres");
        return rateLimiter.wrapWithRateLimit(
                postForMap(
                        "/genres",
                        buildReferenceParams(request, /* allowGenresType= */ true),
                        KodikFunction.GET_INFO));
    }

    public Mono<Map<String, Object>> countriesRaw() {
        return countriesRaw(null);
    }

    public Mono<Map<String, Object>> countriesRaw(KodikReferenceRequest request) {
        log.info("Kodik API /countries");
        return rateLimiter.wrapWithRateLimit(
                postForMap(
                        "/countries",
                        buildReferenceParams(request, /* allowGenresType= */ false),
                        KodikFunction.GET_INFO));
    }

    public Mono<Map<String, Object>> yearsRaw() {
        return yearsRaw(null);
    }

    public Mono<Map<String, Object>> yearsRaw(KodikReferenceRequest request) {
        log.info("Kodik API /years");
        return rateLimiter.wrapWithRateLimit(
                postForMap(
                        "/years",
                        buildReferenceParams(request, /* allowGenresType= */ false),
                        KodikFunction.GET_INFO));
    }

    public Mono<Map<String, Object>> qualitiesRaw() {
        return qualitiesRaw(null);
    }

    public Mono<Map<String, Object>> qualitiesRaw(KodikReferenceRequest request) {
        log.info("Kodik API /qualities/v2");
        return rateLimiter.wrapWithRateLimit(
                postForMap(
                        "/qualities/v2",
                        buildReferenceParams(request, /* allowGenresType= */ false),
                        KodikFunction.GET_INFO));
    }

    /** Package-private for direct unit-testing in {@code KodikApiClientParamWiringTest}. */
    static MultiValueMap<String, String> buildReferenceParams(
            KodikReferenceRequest request, boolean allowGenresType) {
        if (request == null) return emptyParams();
        MultiValueMap<String, String> p = new LinkedMultiValueMap<>();
        if (allowGenresType) {
            addIfPresent(p, "genres_type", request.getGenresType());
        }
        addIfPresent(p, "types", request.getTypes());
        return p;
    }

    // ============= REFERENCE ENDPOINTS (typed, drift-detected) ==============

    public Mono<KodikReferenceResponse<KodikTranslationDto>> translations() {
        return translations(null);
    }

    public Mono<KodikReferenceResponse<KodikTranslationDto>> translations(
            KodikReferenceRequest request) {
        return translationsRaw(request)
                .map(
                        raw ->
                                responseMapper.mapAndDetectChanges(
                                        raw,
                                        new TypeReference<
                                                KodikReferenceResponse<KodikTranslationDto>>() {},
                                        KodikTranslationDto.class));
    }

    public Mono<KodikReferenceResponse<KodikGenreDto>> genres() {
        return genres(null);
    }

    public Mono<KodikReferenceResponse<KodikGenreDto>> genres(KodikReferenceRequest request) {
        return genresRaw(request)
                .map(
                        raw ->
                                responseMapper.mapAndDetectChanges(
                                        raw,
                                        new TypeReference<
                                                KodikReferenceResponse<KodikGenreDto>>() {},
                                        KodikGenreDto.class));
    }

    public Mono<KodikReferenceResponse<KodikCountryDto>> countries() {
        return countries(null);
    }

    public Mono<KodikReferenceResponse<KodikCountryDto>> countries(KodikReferenceRequest request) {
        return countriesRaw(request)
                .map(
                        raw ->
                                responseMapper.mapAndDetectChanges(
                                        raw,
                                        new TypeReference<
                                                KodikReferenceResponse<KodikCountryDto>>() {},
                                        KodikCountryDto.class));
    }

    public Mono<KodikReferenceResponse<KodikYearDto>> years() {
        return years(null);
    }

    public Mono<KodikReferenceResponse<KodikYearDto>> years(KodikReferenceRequest request) {
        return yearsRaw(request)
                .map(
                        raw ->
                                responseMapper.mapAndDetectChanges(
                                        raw,
                                        new TypeReference<
                                                KodikReferenceResponse<KodikYearDto>>() {},
                                        KodikYearDto.class));
    }

    public Mono<KodikReferenceResponse<KodikQualityDto>> qualities() {
        return qualities(null);
    }

    public Mono<KodikReferenceResponse<KodikQualityDto>> qualities(KodikReferenceRequest request) {
        return qualitiesRaw(request)
                .map(
                        raw ->
                                responseMapper.mapAndDetectChanges(
                                        raw,
                                        new TypeReference<
                                                KodikReferenceResponse<KodikQualityDto>>() {},
                                        KodikQualityDto.class));
    }

    // ======================== INTERNAL ========================

    private Mono<Map<String, Object>> postForMap(
            String path, MultiValueMap<String, String> params, KodikFunction function) {
        return executeWithTokenFailover(path, params, function, 0);
    }

    private Mono<Map<String, Object>> executeWithTokenFailover(
            String path,
            MultiValueMap<String, String> paramsWithoutToken,
            KodikFunction function,
            int attempt) {
        String token;
        try {
            token = tokenRegistry.currentToken(function);
        } catch (KodikTokenException.NoWorkingTokenException ex) {
            return Mono.error(ex);
        }

        MultiValueMap<String, String> params = new LinkedMultiValueMap<>(paramsWithoutToken);
        params.add("token", token);

        return kodikApiWebClient
                .post()
                .uri(uriBuilder -> uriBuilder.path(path).queryParams(params).build())
                .retrieve()
                .bodyToMono(
                        new org.springframework.core.ParameterizedTypeReference<
                                Map<String, Object>>() {})
                .flatMap(
                        response -> {
                            Object error = response == null ? null : response.get("error");
                            if (error != null
                                    && KodikTokenValidator.INVALID_TOKEN_ERROR.equals(
                                            error.toString())) {
                                tokenRegistry.markInvalid(token, function);
                                int maxAttempts =
                                        Math.max(
                                                1,
                                                properties
                                                        .getKodik()
                                                        .getTokenFailoverMaxAttempts());
                                if (attempt + 1 >= maxAttempts) {
                                    return Mono.error(
                                            new KodikTokenException.TokenRejectedException(
                                                    "Kodik rejected every token available for"
                                                            + " function "
                                                            + function.getJsonKey()
                                                            + " after "
                                                            + (attempt + 1)
                                                            + " attempt(s)"));
                                }
                                log.warn(
                                        "Kodik rejected token {} for {}, trying next (attempt {})",
                                        KodikTokenRegistry.mask(token),
                                        function.getJsonKey(),
                                        attempt + 2);
                                return executeWithTokenFailover(
                                        path, paramsWithoutToken, function, attempt + 1);
                            }
                            return Mono.just(response);
                        });
    }

    private Mono<Map<String, Object>> postForMapAbsoluteUrl(String absoluteUrl) {
        return kodikApiWebClient
                .post()
                .uri(java.net.URI.create(absoluteUrl))
                .retrieve()
                .bodyToMono(
                        new org.springframework.core.ParameterizedTypeReference<
                                Map<String, Object>>() {});
    }

    private static MultiValueMap<String, String> emptyParams() {
        return new LinkedMultiValueMap<>();
    }

    private KodikFunction pickFunction(KodikSearchRequest r) {
        if (isSet(r.getShikimoriId())
                || isSet(r.getKinopoiskId())
                || isSet(r.getImdbId())
                || isSet(r.getMdlId())
                || isSet(r.getWorldartAnimationId())
                || isSet(r.getWorldartCinemaId())
                || isSet(r.getWorldartLink())
                || isSet(r.getId())) {
            return KodikFunction.BASE_SEARCH_BY_ID;
        }
        return KodikFunction.BASE_SEARCH;
    }

    private static boolean isSet(Object value) {
        if (value == null) return false;
        String str = value.toString();
        return !str.isBlank();
    }

    /** Package-private for direct unit-testing in {@code KodikApiClientParamWiringTest}. */
    static MultiValueMap<String, String> buildSearchParams(KodikSearchRequest r) {
        MultiValueMap<String, String> p = new LinkedMultiValueMap<>();
        // Defaults — caller can override by setting the corresponding fields on KodikSearchRequest.
        // API-4: the previous implementation called p.add(...) unconditionally and then
        // addIfPresent(...), which silently produced two values for the same key when the caller
        // tried to set with_seasons=false. We now respect the caller's value if explicitly set.
        addBooleanWithDefault(p, "with_seasons", r.getWithSeasons(), true);
        addBooleanWithDefault(p, "with_episodes", r.getWithEpisodes(), true);
        addBooleanWithDefault(p, "with_episodes_data", r.getWithEpisodesData(), false);
        addBooleanWithDefault(p, "with_material_data", r.getWithMaterialData(), true);

        addIfPresent(p, "title", r.getTitle());
        addIfPresent(p, "title_orig", r.getTitleOrig());
        addIfPresent(p, "strict", r.getStrict());
        addIfPresent(p, "full_match", r.getFullMatch());
        addIfPresent(p, "id", r.getId());
        addIfPresent(p, "player_link", r.getPlayerLink());
        addIfPresent(p, "kinopoisk_id", r.getKinopoiskId());
        addIfPresent(p, "imdb_id", r.getImdbId());
        addIfPresent(p, "mdl_id", r.getMdlId());
        addIfPresent(p, "worldart_animation_id", r.getWorldartAnimationId());
        addIfPresent(p, "worldart_cinema_id", r.getWorldartCinemaId());
        addIfPresent(p, "worldart_link", r.getWorldartLink());
        addIfPresent(p, "shikimori_id", r.getShikimoriId());
        addIfPresent(p, "limit", r.getLimit());
        addIfPresent(p, "types", r.getTypes());
        addIfPresent(p, "year", r.getYear());
        addIfPresent(p, "translation_id", r.getTranslationId());
        addIfPresent(p, "translation_type", r.getTranslationType());
        addIfPresent(p, "prioritize_translations", r.getPrioritizeTranslations());
        addIfPresent(p, "unprioritize_translations", r.getUnprioritizeTranslations());
        addIfPresent(p, "prioritize_translation_type", r.getPrioritizeTranslationType());
        addIfPresent(p, "has_field", r.getHasField());
        addIfPresent(p, "has_field_and", r.getHasFieldAnd());
        addIfPresent(p, "block_translations", r.getBlockTranslations());
        addIfPresent(p, "camrip", r.getCamrip());
        addIfPresent(p, "lgbt", r.getLgbt());
        addIfPresent(p, "season", r.getSeason());
        addIfPresent(p, "episode", r.getEpisode());
        addIfPresent(p, "with_page_links", r.getWithPageLinks());
        addIfPresent(p, "not_blocked_in", r.getNotBlockedIn());
        addIfPresent(p, "not_blocked_for_me", r.getNotBlockedForMe());
        addIfPresent(p, "countries", r.getCountries());
        addIfPresent(p, "genres", r.getGenres());
        addIfPresent(p, "anime_genres", r.getAnimeGenres());
        addIfPresent(p, "drama_genres", r.getDramaGenres());
        addIfPresent(p, "all_genres", r.getAllGenres());
        addIfPresent(p, "duration", r.getDuration());
        addIfPresent(p, "kinopoisk_rating", r.getKinopoiskRating());
        addIfPresent(p, "imdb_rating", r.getImdbRating());
        addIfPresent(p, "shikimori_rating", r.getShikimoriRating());
        addIfPresent(p, "mydramalist_rating", r.getMydramalistRating());
        addIfPresent(p, "actors", r.getActors());
        addIfPresent(p, "directors", r.getDirectors());
        addIfPresent(p, "producers", r.getProducers());
        addIfPresent(p, "writers", r.getWriters());
        addIfPresent(p, "composers", r.getComposers());
        addIfPresent(p, "editors", r.getEditors());
        addIfPresent(p, "designers", r.getDesigners());
        addIfPresent(p, "operators", r.getOperators());
        addIfPresent(p, "rating_mpaa", r.getRatingMpaa());
        addIfPresent(p, "minimal_age", r.getMinimalAge());
        addIfPresent(p, "anime_kind", r.getAnimeKind());
        addIfPresent(p, "mydramalist_tags", r.getMydramalistTags());
        addIfPresent(p, "anime_status", r.getAnimeStatus());
        addIfPresent(p, "drama_status", r.getDramaStatus());
        addIfPresent(p, "all_status", r.getAllStatus());
        addIfPresent(p, "anime_studios", r.getAnimeStudios());
        addIfPresent(p, "anime_licensed_by", r.getAnimeLicensedBy());

        return p;
    }

    /** Package-private for direct unit-testing in {@code KodikApiClientParamWiringTest}. */
    static MultiValueMap<String, String> buildListParams(KodikListRequest r) {
        MultiValueMap<String, String> p = new LinkedMultiValueMap<>();
        addBooleanWithDefault(p, "with_material_data", r.getWithMaterialData(), true);

        addIfPresent(p, "limit", r.getLimit());
        addIfPresent(p, "sort", r.getSort());
        addIfPresent(p, "order", r.getOrder());
        addIfPresent(p, "types", r.getTypes());
        addIfPresent(p, "year", r.getYear());
        addIfPresent(p, "translation_id", r.getTranslationId());
        addIfPresent(p, "translation_type", r.getTranslationType());
        addIfPresent(p, "has_field", r.getHasField());
        addIfPresent(p, "has_field_and", r.getHasFieldAnd());
        addIfPresent(p, "camrip", r.getCamrip());
        addIfPresent(p, "lgbt", r.getLgbt());
        addIfPresent(p, "with_seasons", r.getWithSeasons());
        addIfPresent(p, "with_episodes", r.getWithEpisodes());
        addIfPresent(p, "with_episodes_data", r.getWithEpisodesData());
        addIfPresent(p, "season", r.getSeason());
        addIfPresent(p, "with_page_links", r.getWithPageLinks());
        addIfPresent(p, "not_blocked_in", r.getNotBlockedIn());
        addIfPresent(p, "not_blocked_for_me", r.getNotBlockedForMe());
        addIfPresent(p, "countries", r.getCountries());
        addIfPresent(p, "genres", r.getGenres());
        addIfPresent(p, "anime_genres", r.getAnimeGenres());
        addIfPresent(p, "drama_genres", r.getDramaGenres());
        addIfPresent(p, "all_genres", r.getAllGenres());
        addIfPresent(p, "duration", r.getDuration());
        addIfPresent(p, "kinopoisk_rating", r.getKinopoiskRating());
        addIfPresent(p, "imdb_rating", r.getImdbRating());
        addIfPresent(p, "shikimori_rating", r.getShikimoriRating());
        addIfPresent(p, "mydramalist_rating", r.getMydramalistRating());
        addIfPresent(p, "actors", r.getActors());
        addIfPresent(p, "directors", r.getDirectors());
        addIfPresent(p, "producers", r.getProducers());
        addIfPresent(p, "writers", r.getWriters());
        addIfPresent(p, "composers", r.getComposers());
        addIfPresent(p, "editors", r.getEditors());
        addIfPresent(p, "designers", r.getDesigners());
        addIfPresent(p, "operators", r.getOperators());
        addIfPresent(p, "rating_mpaa", r.getRatingMpaa());
        addIfPresent(p, "minimal_age", r.getMinimalAge());
        addIfPresent(p, "anime_kind", r.getAnimeKind());
        addIfPresent(p, "mydramalist_tags", r.getMydramalistTags());
        addIfPresent(p, "anime_status", r.getAnimeStatus());
        addIfPresent(p, "drama_status", r.getDramaStatus());
        addIfPresent(p, "all_status", r.getAllStatus());
        addIfPresent(p, "anime_studios", r.getAnimeStudios());
        addIfPresent(p, "anime_licensed_by", r.getAnimeLicensedBy());

        return p;
    }

    private static void addIfPresent(
            MultiValueMap<String, String> params, String key, Object value) {
        if (value == null) return;
        String str = value.toString();
        if (!str.isBlank()) {
            params.add(key, str);
        }
    }

    private static void addBooleanWithDefault(
            MultiValueMap<String, String> params,
            String key,
            Boolean explicitValue,
            boolean defaultValue) {
        boolean v = explicitValue != null ? explicitValue : defaultValue;
        params.add(key, Boolean.toString(v));
    }
}
