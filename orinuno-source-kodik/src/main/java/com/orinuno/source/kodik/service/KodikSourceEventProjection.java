/*
 * KodikSourceEventProjection — ADR 0018 Phase 2.6.
 *
 * Reads kodik_content + kodik_episode_variant rows from this service's own MySQL schema
 * and renders them as producer-side SourceCatalogEvent payloads. Consumer (the external meter
 * source-bridge or the future OSS meter) polls via GET /api/v1/source-events/ready and
 * decides what to do with the events.
 *
 * Architectural shape (per ADR 0018):
 *   - This service does NOT own episode_video — that table lives in orinuno-app until
 *     Phase 5 hands it to the OSS meter shared catalog DB. So we cannot enrich events
 *     with decoded mp4 URLs from here.
 *   - Instead, every emitted variant carries the long-lived kodikLink iframe URL as
 *     SourceEpisodeVariant#mediaUrl. The consumer that needs the bytes performs a JIT
 *     decode against that iframe URL (TECH_DEBT ARCH-0018) — the iframe URL stays
 *     stable for the lifetime of the Kodik catalog entry, so this contract is durable.
 */
package com.orinuno.source.kodik.service;

import com.orinuno.contract.source.SourceCatalogEvent;
import com.orinuno.source.kodik.mapper.KodikSourceEventMapper;
import com.orinuno.source.kodik.model.KodikContent;
import com.orinuno.source.kodik.model.KodikEpisodeVariant;
import com.orinuno.source.kodik.repository.ContentRepository;
import com.orinuno.source.kodik.repository.EpisodeVariantRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Producer-side projection that turns L1 Kodik rows (kodik_content + kodik_episode_variant) into
 * the {@link SourceCatalogEvent} sealed hierarchy from {@code orinuno-source-contract}.
 *
 * <p>This is the source-kodik counterpart of orinuno-app's {@code
 * ExportDataService.findReadyForExportAsEvents}. The orinuno-app version performs an inner join
 * against {@code episode_video} (Phase 0.4b/c read-side) so each variant carries the decoded {@code
 * mp4Link} ready for the consumer to download. We deliberately skip that join here — {@code
 * episode_video} does not exist in this service's schema and the architectural goal is to push
 * decoded-URL responsibility downstream into the meter (Phase 5).
 *
 * <p>Variant filter applied by {@link KodikSourceEventMapper#toEvent} is {@code kodikLink != null}
 * — every L1 variant that came back from {@code /search} qualifies, regardless of whether it has
 * ever been decoded. Consumers receive the iframe URL and own the decode timing decision
 * themselves.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KodikSourceEventProjection {

    private final ContentRepository contentRepository;
    private final EpisodeVariantRepository episodeVariantRepository;
    private final Clock clock;

    /**
     * Page through the Kodik content table newest-first, building one {@link SourceCatalogEvent}
     * per row. The {@code updatedSince} watermark is the contract for incremental polling —
     * consumers pass the {@code Provenance.fetchedAt} of the most recent event they processed and
     * re-poll only for newer rows.
     *
     * @param updatedSince inclusive watermark on {@code kodik_content.updated_at}; {@code null}
     *     means "everything"
     * @param limit upper bound on the returned list size (the call walks pages of this many content
     *     rows)
     */
    public List<SourceCatalogEvent> findReadyEvents(LocalDateTime updatedSince, int limit) {
        List<KodikContent> readyContent =
                contentRepository.findReadyForExport(0, limit, updatedSince);
        log.debug(
                "KodikSourceEventProjection: rendering {} content row(s) (updatedSince={})",
                readyContent.size(),
                updatedSince);
        return readyContent.stream()
                .map(
                        content -> {
                            List<KodikEpisodeVariant> variants =
                                    episodeVariantRepository.findByContentId(content.getId());
                            return KodikSourceEventMapper.toEvent(content, variants, clock);
                        })
                .toList();
    }
}
