package com.orinuno.controller;

import com.orinuno.catalog.readonly.CatalogEpisodeSourceReadRepository;
import com.orinuno.catalog.readonly.CatalogEpisodeVideoReadRepository;
import com.orinuno.jutsu.JutsuClient;
import com.orinuno.jutsu.drift.JutsuDriftHealth;
import com.orinuno.model.EpisodeSource;
import com.orinuno.model.EpisodeVideo;
import com.orinuno.model.KodikContent;
import com.orinuno.repository.ContentRepository;
import com.orinuno.service.orchestration.MultiSourceRanker;
import com.orinuno.service.orchestration.MultiSourceRanker.RankedCandidate;
import com.orinuno.service.orchestration.MultiSourceRanker.RankingPreferences;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * AP-7 (ADR 0008) — multi-source orchestration HTTP surface. Returns the ranked candidate list for
 * a single (content, season, episode) tuple.
 *
 * <p>The {@code prefer} query parameter accepts a comma-separated provider order override (e.g.
 * {@code ?prefer=ANIBOOM,KODIK,SIBNET,JUTSU}) for clients that want to demote Kodik in favour of a
 * specific alternative.
 *
 * <p><strong>API tier</strong>: the canonical resource-style path is {@code
 * /api/v1/anime/{contentId}/episodes/{season}/{episode}/sources}. A by-kinopoisk variant lives
 * alongside it. The original short path {@code /api/v1/sources/{contentId}/{season}/{episode}} is
 * kept as a deprecated alias to avoid breaking the demo UI in-flight; remove after the API/module
 * split lands.
 */
@Slf4j
@RestController
@RequiredArgsConstructor
// ADR 0021 §C1.4 — controller depends on the meter-readonly DS (Phase 5.4) for L2 reads.
// Monolith deploys without orinuno.catalog-read.url set have no catalogReadJdbcTemplate
// bean → the two CatalogEpisode*ReadRepository beans are also absent → this controller
// is not wired and /api/v1/anime/* returns 404. Acceptable for OSS-contributor dev
// boots where the ranker isn't the demo path; the docker-compose.yml full-split topology
// always has the readonly DS configured.
@org.springframework.boot.autoconfigure.condition.ConditionalOnBean(
        name = "catalogReadJdbcTemplate")
@Tag(name = "Multi-source", description = "AP-7: ranked provider candidates for an episode")
public class MultiSourceController {

    private final CatalogEpisodeSourceReadRepository sourceRepository;
    private final CatalogEpisodeVideoReadRepository videoRepository;
    private final MultiSourceRanker ranker;
    // ADR 0021 §C1.3 — dropped the legacy ContentService dependency; the ranker only ever
    // needed content_id from kinopoiskId, which is a one-column lookup on ContentRepository.
    // The read against kodik_content stays orinuno-schema for now (kodik_content has a live
    // writer via ContentService.findOrCreateContent in ParserService +
    // KodikDumpBootstrapService); it moves to source-kodik with Block D.
    private final ContentRepository contentRepository;
    private final JutsuClient jutsuClient;

    @GetMapping("/api/v1/anime/{contentId}/episodes/{season}/{episode}/sources")
    @Operation(
            summary = "Ranked provider candidates for an episode (canonical resource path)",
            description =
                    "Returns episode_source + episode_video rows joined and scored by"
                        + " MultiSourceRanker (AP-7, ADR 0008). Higher score = better choice. This"
                        + " is the canonical resource-style path; the legacy alias is GET"
                        + " /api/v1/sources/{contentId}/{season}/{episode}.")
    public Mono<ResponseEntity<Map<String, Object>>> rankedByContentId(
            @PathVariable Long contentId,
            @PathVariable Integer season,
            @PathVariable Integer episode,
            @Parameter(
                            description =
                                    "Optional comma-separated provider order override (e.g."
                                            + " ANIBOOM,KODIK,SIBNET,JUTSU)")
                    @RequestParam(required = false)
                    String prefer) {
        return rankedFor(contentId, season, episode, prefer);
    }

    @GetMapping("/api/v1/anime/by-kinopoisk/{kinopoiskId}/episodes/{season}/{episode}/sources")
    @Operation(
            summary = "Ranked provider candidates for an episode, looked up by kinopoisk id",
            description =
                    "Same payload as the by-contentId variant, but lets external integrations skip"
                        + " the contentId lookup. Returns 404 if no kodik_content row matches the"
                        + " given kinopoiskId.")
    public Mono<ResponseEntity<Map<String, Object>>> rankedByKinopoiskId(
            @PathVariable String kinopoiskId,
            @PathVariable Integer season,
            @PathVariable Integer episode,
            @RequestParam(required = false) String prefer) {
        return Mono.fromCallable(() -> contentRepository.findByKinopoiskId(kinopoiskId))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(
                        (Optional<KodikContent> row) -> {
                            if (row.isEmpty()) {
                                Map<String, Object> body = new LinkedHashMap<>();
                                body.put("error", "kinopoiskId not found");
                                body.put("kinopoiskId", kinopoiskId);
                                return Mono.just(ResponseEntity.status(404).body(body));
                            }
                            return rankedFor(row.get().getId(), season, episode, prefer);
                        });
    }

    @GetMapping("/api/v1/sources/{contentId}/{season}/{episode}")
    @Operation(
            deprecated = true,
            summary = "[Deprecated] use /api/v1/anime/{contentId}/episodes/{s}/{e}/sources",
            description =
                    "Legacy short path kept so the demo UI keeps working during the API/module"
                            + " split. Same response shape, same semantics. Remove after the new"
                            + " path is documented and consumers migrate.")
    @Deprecated
    public Mono<ResponseEntity<Map<String, Object>>> ranked(
            @PathVariable Long contentId,
            @PathVariable Integer season,
            @PathVariable Integer episode,
            @RequestParam(required = false) String prefer) {
        return rankedFor(contentId, season, episode, prefer);
    }

    private Mono<ResponseEntity<Map<String, Object>>> rankedFor(
            Long contentId, Integer season, Integer episode, String prefer) {
        return Mono.fromCallable(
                        () -> {
                            List<EpisodeSource> sources =
                                    sourceRepository.findByEpisode(contentId, season, episode);
                            if (sources.isEmpty()) {
                                return ResponseEntity.ok(emptyBody(contentId, season, episode));
                            }
                            List<EpisodeVideo> videos = new ArrayList<>();
                            for (EpisodeSource s : sources) {
                                videos.addAll(videoRepository.findBySource(s.getId()));
                            }
                            RankingPreferences prefs = RankingPreferences.defaults();
                            if (prefer != null && !prefer.isBlank()) {
                                List<String> order =
                                        Arrays.stream(prefer.split(","))
                                                .map(String::trim)
                                                .filter(s -> !s.isEmpty())
                                                .map(String::toUpperCase)
                                                .collect(Collectors.toList());
                                if (!order.isEmpty()) {
                                    prefs.providerOrder = order;
                                }
                            }
                            // Auto-demote jut.su when its SDK drift detector is unhappy. Demoted
                            // providers stay in the candidate list (so we can still pick them as
                            // a last resort) but receive providerScore=0 — they fall to the
                            // bottom of the ranking. Catches "the site changed its DOM" without
                            // needing manual intervention.
                            JutsuDriftHealth jutsuHealth = jutsuClient.getDriftSnapshot().health();
                            if (jutsuHealth != JutsuDriftHealth.HEALTHY) {
                                log.info(
                                        "Demoting JUTSU in ranker — drift health is {}",
                                        jutsuHealth);
                                prefs.demotedProviders = Set.of(EpisodeSource.Provider.JUTSU);
                            }
                            List<RankedCandidate> ranked = ranker.rank(sources, videos, prefs);
                            return ResponseEntity.ok(toBody(contentId, season, episode, ranked));
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }

    private static Map<String, Object> emptyBody(Long contentId, Integer season, Integer episode) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("contentId", contentId);
        body.put("season", season);
        body.put("episode", episode);
        body.put("candidates", List.of());
        body.put("count", 0);
        return body;
    }

    private static Map<String, Object> toBody(
            Long contentId, Integer season, Integer episode, List<RankedCandidate> ranked) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("contentId", contentId);
        body.put("season", season);
        body.put("episode", episode);
        body.put("count", ranked.size());
        List<Map<String, Object>> rows = new ArrayList<>();
        for (RankedCandidate c : ranked) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("provider", c.source().getProvider());
            row.put("translatorId", c.source().getTranslatorId());
            row.put("translatorName", c.source().getTranslatorName());
            row.put("quality", c.video().getQuality());
            row.put("videoUrl", c.video().getVideoUrl());
            row.put("videoFormat", c.video().getVideoFormat());
            row.put("decodedAt", c.video().getDecodedAt());
            row.put("decodeMethod", c.video().getDecodeMethod());
            row.put("decodeFailedCount", c.video().getDecodeFailedCount());
            row.put("score", c.score());
            rows.add(row);
        }
        body.put("candidates", rows);
        return body;
    }
}
