package com.orinuno.service;

import com.orinuno.model.EpisodeSource;
import com.orinuno.model.EpisodeVideo;
import com.orinuno.model.KodikEpisodeVariant;
import com.orinuno.repository.EpisodeSourceRepository;
import com.orinuno.repository.EpisodeVideoRepository;
import com.orinuno.service.decoder.DecodeAttemptResult;
import java.time.LocalDateTime;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * PLAYER-1 (ADR 0005) Phase A — dual-write helper. Existing callers persist Kodik decode results to
 * {@code kodik_episode_variant}; this service mirrors the same write into the new {@code
 * episode_source} + {@code episode_video} schema so consumers can switch read-side over without
 * missing any rows.
 *
 * <p>Repositories are looked up via {@link ObjectProvider} so unit tests can wire {@code null} and
 * the dual-write turns into a no-op. Failures are caught + logged — dual-write never blocks the
 * primary write path.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KodikEpisodeDualWriteService {

    private final ObjectProvider<EpisodeSourceRepository> sourceRepositoryProvider;
    private final ObjectProvider<EpisodeVideoRepository> videoRepositoryProvider;

    /**
     * Mirror a Kodik decode result into the new schema.
     *
     * @param variant the Kodik episode variant we just decoded
     * @param result decoder result (carrying the picked quality + URL)
     * @param pickedQuality the quality bucket the caller chose for {@code mp4_link}
     * @param pickedUrl the URL persisted to {@code mp4_link}
     */
    public void mirrorDecode(
            KodikEpisodeVariant variant,
            DecodeAttemptResult result,
            String pickedQuality,
            String pickedUrl) {
        if (variant == null || pickedUrl == null || pickedQuality == null) {
            return;
        }
        EpisodeSourceRepository sourceRepo = sourceRepositoryProvider.getIfAvailable();
        EpisodeVideoRepository videoRepo = videoRepositoryProvider.getIfAvailable();
        if (sourceRepo == null || videoRepo == null) {
            return;
        }
        try {
            LocalDateTime now = LocalDateTime.now();
            EpisodeSource source = buildSource(variant, now);
            sourceRepo.upsert(source);
            Long sourceId = source.getId();
            if (sourceId == null) {
                log.warn(
                        "PLAYER-1 dual-write: upsert returned null id for variant id={} (skipping"
                                + " video row)",
                        variant.getId());
                return;
            }
            String method =
                    result != null && result.method() != null ? result.method().name() : null;
            EpisodeVideo video =
                    EpisodeVideo.builder()
                            .sourceId(sourceId)
                            .quality(pickedQuality)
                            .videoUrl(pickedUrl)
                            .videoFormat(inferFormat(pickedUrl))
                            .decodedAt(now)
                            .decodeMethod(method)
                            .decodeFailedCount(0)
                            .build();
            videoRepo.upsertDecoded(video);
            if (result != null && result.qualities() != null && result.qualities().size() > 1) {
                for (Map.Entry<String, String> entry : result.qualities().entrySet()) {
                    String key = entry.getKey();
                    String url = entry.getValue();
                    if (key == null || key.equals(pickedQuality) || url == null) {
                        continue;
                    }
                    EpisodeVideo extra =
                            EpisodeVideo.builder()
                                    .sourceId(sourceId)
                                    .quality(key)
                                    .videoUrl(url)
                                    .videoFormat(inferFormat(url))
                                    .decodedAt(now)
                                    .decodeMethod(method)
                                    .decodeFailedCount(0)
                                    .build();
                    videoRepo.upsertDecoded(extra);
                }
            }
        } catch (Exception ex) {
            log.warn(
                    "PLAYER-1 dual-write: mirror failed for variant id={}: {}",
                    variant.getId(),
                    ex.toString());
        }
    }

    static EpisodeSource buildSource(KodikEpisodeVariant variant, LocalDateTime now) {
        return EpisodeSource.builder()
                .contentId(variant.getContentId())
                .season(variant.getSeasonNumber())
                .episode(variant.getEpisodeNumber())
                .translatorId(
                        variant.getTranslationId() == null
                                ? null
                                : String.valueOf(variant.getTranslationId()))
                .translatorName(variant.getTranslationTitle())
                .provider(EpisodeSource.Provider.KODIK)
                .sourceUrl(variant.getKodikLink())
                .sourceType(null)
                .discoveredAt(now)
                .lastSeenAt(now)
                .build();
    }

    static String inferFormat(String url) {
        if (url == null) {
            return null;
        }
        String lower = url.toLowerCase();
        if (lower.contains(".m3u8")) {
            return "application/x-mpegURL";
        }
        if (lower.contains(".mpd")) {
            return "application/dash+xml";
        }
        if (lower.contains(".mp4")) {
            return "video/mp4";
        }
        return null;
    }
}
