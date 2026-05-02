package com.orinuno.service.orchestration;

import com.orinuno.model.EpisodeSource;
import com.orinuno.model.EpisodeVideo;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * AP-7 (ADR 0008) — multi-source ranker. Given the set of {@link EpisodeSource} + {@link
 * EpisodeVideo} rows for a single episode (across providers), produce a deterministic ordered list
 * of "best to worst" video candidates.
 *
 * <p>Score is the weighted sum of:
 *
 * <ol>
 *   <li><b>Provider preference</b> (default ordering: KODIK &gt; ANIBOOM &gt; JUTSU &gt; SIBNET).
 *       Caller can override via {@link RankingPreferences#providerOrder}.
 *   <li><b>Freshness</b> — recently decoded URLs win (CDN tokens expire).
 *   <li><b>Quality</b> — higher numeric quality wins; "auto" / "dash" tie-break to lowest.
 *   <li><b>Reliability</b> — high {@code decode_failed_count} demotes a candidate.
 * </ol>
 *
 * <p>Ranker is pure: no I/O, no logging side effects, no time of-day. Decoupled from the data
 * source so callers can use it for any (sources, videos) snapshot they have in hand.
 */
@Component
public class MultiSourceRanker {

    public List<RankedCandidate> rank(
            List<EpisodeSource> sources, List<EpisodeVideo> videos, RankingPreferences prefs) {
        if (sources == null || sources.isEmpty() || videos == null || videos.isEmpty()) {
            return List.of();
        }
        RankingPreferences effectivePrefs = prefs == null ? RankingPreferences.defaults() : prefs;
        Map<Long, EpisodeSource> sourceById = new LinkedHashMap<>();
        for (EpisodeSource s : sources) {
            if (s != null && s.getId() != null) {
                sourceById.put(s.getId(), s);
            }
        }
        LocalDateTime now = effectivePrefs.now == null ? LocalDateTime.now() : effectivePrefs.now;
        List<RankedCandidate> scored = new ArrayList<>();
        for (EpisodeVideo v : videos) {
            if (v == null || v.getSourceId() == null) {
                continue;
            }
            EpisodeSource src = sourceById.get(v.getSourceId());
            if (src == null) {
                continue;
            }
            if (v.getVideoUrl() == null || v.getVideoUrl().isBlank()) {
                continue;
            }
            double score = score(src, v, effectivePrefs, now);
            scored.add(new RankedCandidate(src, v, score));
        }
        scored.sort(Comparator.comparingDouble(RankedCandidate::score).reversed());
        return List.copyOf(scored);
    }

    private double score(
            EpisodeSource src, EpisodeVideo v, RankingPreferences prefs, LocalDateTime now) {
        double providerScore = providerScore(src.getProvider(), prefs.providerOrder);
        double freshnessScore = freshnessScore(v.getDecodedAt(), now);
        double qualityScore = qualityScore(v.getQuality());
        double reliabilityScore = reliabilityScore(v.getDecodeFailedCount());
        return prefs.weightProvider * providerScore
                + prefs.weightFreshness * freshnessScore
                + prefs.weightQuality * qualityScore
                + prefs.weightReliability * reliabilityScore;
    }

    static double providerScore(String provider, List<String> order) {
        if (provider == null || order == null || order.isEmpty()) {
            return 0.0;
        }
        int idx = order.indexOf(provider);
        if (idx < 0) {
            return 0.0;
        }
        return 1.0 - ((double) idx / order.size());
    }

    static double freshnessScore(LocalDateTime decodedAt, LocalDateTime now) {
        if (decodedAt == null) {
            return 0.0;
        }
        long minutes = Duration.between(decodedAt, now).toMinutes();
        if (minutes <= 0) {
            return 1.0;
        }
        if (minutes >= 6 * 60L) {
            return 0.0;
        }
        return 1.0 - ((double) minutes / (6 * 60));
    }

    static double qualityScore(String quality) {
        if (quality == null) {
            return 0.0;
        }
        if (quality.equals("auto")) {
            return 0.4;
        }
        if (quality.equals("dash")) {
            return 0.3;
        }
        try {
            int q = Integer.parseInt(quality.replaceAll("(?i)p$", ""));
            return Math.min(1.0, q / 1080.0);
        } catch (NumberFormatException ignored) {
            return 0.0;
        }
    }

    static double reliabilityScore(Integer failedCount) {
        int f = failedCount == null ? 0 : failedCount;
        if (f <= 0) {
            return 1.0;
        }
        if (f >= 5) {
            return 0.0;
        }
        return 1.0 - (f / 5.0);
    }

    public record RankedCandidate(EpisodeSource source, EpisodeVideo video, double score) {}

    /**
     * Tunable ranking inputs. {@link #defaults()} returns the default policy: provider weight 0.4,
     * freshness 0.3, quality 0.2, reliability 0.1; provider order KODIK / ANIBOOM / JUTSU / SIBNET.
     */
    public static final class RankingPreferences {
        public List<String> providerOrder;
        public double weightProvider = 0.4;
        public double weightFreshness = 0.3;
        public double weightQuality = 0.2;
        public double weightReliability = 0.1;

        /** Optional clock pin for deterministic tests. */
        public LocalDateTime now;

        public static RankingPreferences defaults() {
            RankingPreferences p = new RankingPreferences();
            p.providerOrder =
                    List.of(
                            EpisodeSource.Provider.KODIK,
                            EpisodeSource.Provider.ANIBOOM,
                            EpisodeSource.Provider.JUTSU,
                            EpisodeSource.Provider.SIBNET);
            return p;
        }
    }
}
