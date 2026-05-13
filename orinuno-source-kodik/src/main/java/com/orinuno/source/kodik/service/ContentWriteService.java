/*
 * ContentWriteService — ADR 0021 §D1c.
 *
 * Write half of orinuno-app's legacy ContentService, ported into
 * source-kodik. Handles the L1 Kodik upsert path
 * (findOrCreateContent + saveVariants) that ParserService + (future
 * D5) KodikDumpBootstrapService drive. The read half moved to
 * ContentReadService in C1.1; both halves live behind separate beans
 * in source-kodik to keep responsibilities crisp.
 */
package com.orinuno.source.kodik.service;

import com.orinuno.source.kodik.model.KodikContent;
import com.orinuno.source.kodik.model.KodikEpisodeVariant;
import com.orinuno.source.kodik.repository.ContentRepository;
import com.orinuno.source.kodik.repository.EpisodeVariantRepository;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class ContentWriteService {

    private final ContentRepository contentRepository;
    private final EpisodeVariantRepository episodeVariantRepository;

    /**
     * PF7: Find or create content, with fallback grouping by (title, year) if kinopoisk_id is
     * absent.
     *
     * <p>ADR 0018 Phase 5.6 — the catalog-ingestion hand-off lives in meter now. This service only
     * owns the L1 Kodik row; the L1 → L3 bridge is served by source-kodik's {@code
     * /api/v1/source-events/ready} stream which meter polls.
     */
    public KodikContent findOrCreateContent(KodikContent content) {
        if (content.getKinopoiskId() != null && !content.getKinopoiskId().isBlank()) {
            Optional<KodikContent> existing =
                    contentRepository.findByKinopoiskId(content.getKinopoiskId());
            if (existing.isPresent()) {
                KodikContent found = existing.get();
                content.setId(found.getId());
                contentRepository.update(content);
                return content;
            }
        } else {
            log.warn(
                    "Content without kinopoisk_id: '{}' ({}). Using (title, year) fallback.",
                    content.getTitle(),
                    content.getYear());

            if (content.getTitle() != null && content.getYear() != null) {
                Optional<KodikContent> existing =
                        contentRepository.findByTitleAndYear(content.getTitle(), content.getYear());
                if (existing.isPresent()) {
                    KodikContent found = existing.get();
                    content.setId(found.getId());
                    contentRepository.update(content);
                    return content;
                }
            }
        }

        contentRepository.insert(content);
        log.info("Created new content: id={}, title='{}'", content.getId(), content.getTitle());
        return content;
    }

    public void saveVariants(List<KodikEpisodeVariant> variants) {
        if (variants.isEmpty()) return;
        for (KodikEpisodeVariant variant : variants) {
            episodeVariantRepository.upsertWithCoalesce(variant);
        }
        log.info(
                "Saved {} episode variants for content_id={}",
                variants.size(),
                variants.get(0).getContentId());
    }
}
