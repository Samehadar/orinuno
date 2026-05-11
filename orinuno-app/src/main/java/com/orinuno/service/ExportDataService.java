package com.orinuno.service;

import com.orinuno.contract.source.SourceCatalogEvent;
import com.orinuno.mapper.ContentMapper;
import com.orinuno.mapper.SourceEventMapper;
import com.orinuno.model.KodikContent;
import com.orinuno.model.KodikEpisodeVariant;
import com.orinuno.model.dto.ContentExportDto;
import com.orinuno.model.dto.PageRequest;
import com.orinuno.model.dto.PageResponse;
import com.orinuno.repository.ContentRepository;
import com.orinuno.repository.EpisodeVariantRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class ExportDataService {

    private final ContentRepository contentRepository;
    private final EpisodeVariantRepository episodeVariantRepository;
    private final Clock clock = Clock.systemUTC();

    public Optional<ContentExportDto> getExportData(Long contentId) {
        Optional<KodikContent> contentOpt = contentRepository.findById(contentId);
        if (contentOpt.isEmpty()) {
            return Optional.empty();
        }

        KodikContent content = contentOpt.get();
        // ADR 0018 Phase 0.4c — variant.mp4Link no longer lives in kodik_episode_variant.
        // findByContentIdWithDecodedVideo INNER-JOINs episode_video and returns variants
        // with mp4Link / mp4LinkDecodedAt / decodeMethod populated from the joined columns.
        // Variants without a decoded video URL are filtered out at the SQL level, so the
        // in-memory filter from Phase 0.4b becomes a no-op (kept removed).
        List<KodikEpisodeVariant> decodedVariants =
                episodeVariantRepository.findByContentIdWithDecodedVideo(contentId);

        if (decodedVariants.isEmpty()) {
            log.debug("📭 Content id={} has no decoded variants yet", contentId);
        }

        return Optional.of(ContentMapper.toExportDto(content, decodedVariants));
    }

    public PageResponse<ContentExportDto> getReadyForExport(
            PageRequest pageRequest, LocalDateTime updatedSince) {
        int offset = pageRequest.getPage() * pageRequest.getSize();

        List<KodikContent> readyContent =
                contentRepository.findReadyForExport(offset, pageRequest.getSize(), updatedSince);
        long total = contentRepository.countReadyForExport(updatedSince);
        int totalPages = (int) Math.ceil((double) total / pageRequest.getSize());

        // ADR 0018 Phase 0.4c — findByContentIdWithDecodedVideo replaces
        // findByContentId + in-memory mp4Link filter. SQL-level INNER JOIN keeps only the
        // variants that carry a populated episode_video.video_url; the returned variants
        // have mp4Link populated from the joined column for downstream DTO assembly.
        List<ContentExportDto> exportData =
                readyContent.stream()
                        .map(
                                content -> {
                                    List<KodikEpisodeVariant> variants =
                                            episodeVariantRepository
                                                    .findByContentIdWithDecodedVideo(
                                                            content.getId());
                                    return ContentMapper.toExportDto(content, variants);
                                })
                        .toList();

        return PageResponse.<ContentExportDto>builder()
                .content(exportData)
                .page(pageRequest.getPage())
                .size(pageRequest.getSize())
                .totalElements(total)
                .totalPages(totalPages)
                .build();
    }

    /**
     * Stage B of ARCH-0017: render ready-for-export L1 rows as producer-side {@link
     * SourceCatalogEvent}s for direct consumption by the external aggregator's{@code external bridge} (and any
     * future open consumer). Same query and same variant filter as {@link
     * #getReadyForExport(PageRequest, LocalDateTime)} — different output shape.
     *
     * <p>Pagination is collapsed into a single {@code limit} parameter — the eventing endpoint is
     * intended for incremental polling against {@code updatedSince}, so callers want a flat window,
     * not a {@link PageResponse} envelope.
     */
    public List<SourceCatalogEvent> findReadyForExportAsEvents(
            LocalDateTime updatedSince, int limit) {
        List<KodikContent> readyContent =
                contentRepository.findReadyForExport(0, limit, updatedSince);
        return readyContent.stream()
                .map(
                        content -> {
                            // ADR 0018 Phase 0.4c — same JOIN-backed fetch as the export DTO
                            // path: only variants with a populated episode_video are emitted,
                            // and they carry mp4Link populated from the joined column so
                            // SourceEventMapper's downstream mediaUrl projection stays unchanged.
                            List<KodikEpisodeVariant> variants =
                                    episodeVariantRepository.findByContentIdWithDecodedVideo(
                                            content.getId());
                            return SourceEventMapper.toEvent(content, variants, clock);
                        })
                .toList();
    }
}
