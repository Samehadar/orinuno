package com.orinuno.controller;

import com.orinuno.model.EpisodeSource;
import com.orinuno.model.EpisodeVideo;
import com.orinuno.repository.EpisodeSourceRepository;
import com.orinuno.repository.EpisodeVideoRepository;
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
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
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
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/sources")
@RequiredArgsConstructor
@Tag(name = "Multi-source", description = "AP-7: ranked provider candidates for an episode")
public class MultiSourceController {

    private final EpisodeSourceRepository sourceRepository;
    private final EpisodeVideoRepository videoRepository;
    private final MultiSourceRanker ranker;

    @GetMapping("/{contentId}/{season}/{episode}")
    @Operation(
            summary = "Ranked provider candidates for an episode",
            description =
                    "Returns episode_source + episode_video rows joined and scored by"
                            + " MultiSourceRanker (AP-7, ADR 0008). Higher score = better choice.")
    public Mono<ResponseEntity<Map<String, Object>>> ranked(
            @PathVariable Long contentId,
            @PathVariable Integer season,
            @PathVariable Integer episode,
            @Parameter(
                            description =
                                    "Optional comma-separated provider order override (e.g."
                                            + " ANIBOOM,KODIK,SIBNET,JUTSU)")
                    @RequestParam(required = false)
                    String prefer) {
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
