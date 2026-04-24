package com.orinuno.client;

import com.orinuno.client.dto.KodikListRequest;
import com.orinuno.client.dto.KodikSearchRequest;
import com.orinuno.client.dto.KodikSearchResponse;
import com.orinuno.configuration.OrinunoProperties;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

@Slf4j
@Component
@RequiredArgsConstructor
public class KodikApiClient {

    private final WebClient kodikApiWebClient;
    private final OrinunoProperties properties;
    private final KodikResponseMapper responseMapper;
    private final KodikApiRateLimiter rateLimiter;

    // ======================== SEARCH ========================

    public Mono<Map<String, Object>> searchRaw(KodikSearchRequest request) {
        MultiValueMap<String, String> params = buildSearchParams(request);
        log.info("Kodik API /search with {} params", params.size());
        return rateLimiter.wrapWithRateLimit(postForMap("/search", params));
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
        if (request.getNextPageUrl() != null && !request.getNextPageUrl().isBlank()) {
            log.info("Kodik API /list next page");
            return rateLimiter.wrapWithRateLimit(postForMapAbsoluteUrl(request.getNextPageUrl()));
        }
        MultiValueMap<String, String> params = buildListParams(request);
        log.info("Kodik API /list with {} params", params.size());
        return rateLimiter.wrapWithRateLimit(postForMap("/list", params));
    }

    // ======================== REFERENCE ENDPOINTS ========================

    public Mono<Map<String, Object>> translationsRaw() {
        log.info("Kodik API /translations/v2");
        return rateLimiter.wrapWithRateLimit(postForMap("/translations/v2", tokenOnly()));
    }

    public Mono<Map<String, Object>> genresRaw() {
        log.info("Kodik API /genres");
        return rateLimiter.wrapWithRateLimit(postForMap("/genres", tokenOnly()));
    }

    public Mono<Map<String, Object>> countriesRaw() {
        log.info("Kodik API /countries");
        return rateLimiter.wrapWithRateLimit(postForMap("/countries", tokenOnly()));
    }

    public Mono<Map<String, Object>> yearsRaw() {
        log.info("Kodik API /years");
        return rateLimiter.wrapWithRateLimit(postForMap("/years", tokenOnly()));
    }

    public Mono<Map<String, Object>> qualitiesRaw() {
        log.info("Kodik API /qualities/v2");
        return rateLimiter.wrapWithRateLimit(postForMap("/qualities/v2", tokenOnly()));
    }

    // ======================== INTERNAL ========================

    private Mono<Map<String, Object>> postForMap(
            String path, MultiValueMap<String, String> params) {
        return kodikApiWebClient
                .post()
                .uri(uriBuilder -> uriBuilder.path(path).queryParams(params).build())
                .retrieve()
                .bodyToMono(
                        new org.springframework.core.ParameterizedTypeReference<
                                Map<String, Object>>() {});
    }

    private Mono<Map<String, Object>> postForMapAbsoluteUrl(String absoluteUrl) {
        return kodikApiWebClient
                .post()
                .uri(absoluteUrl)
                .retrieve()
                .bodyToMono(
                        new org.springframework.core.ParameterizedTypeReference<
                                Map<String, Object>>() {});
    }

    private MultiValueMap<String, String> tokenOnly() {
        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.add("token", properties.getKodik().getToken());
        return params;
    }

    private MultiValueMap<String, String> buildSearchParams(KodikSearchRequest r) {
        MultiValueMap<String, String> p = new LinkedMultiValueMap<>();
        p.add("token", properties.getKodik().getToken());
        p.add("with_seasons", "true");
        p.add("with_episodes", "true");
        p.add("with_material_data", "true");

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

    private MultiValueMap<String, String> buildListParams(KodikListRequest r) {
        MultiValueMap<String, String> p = new LinkedMultiValueMap<>();
        p.add("token", properties.getKodik().getToken());
        p.add("with_material_data", "true");

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
}
