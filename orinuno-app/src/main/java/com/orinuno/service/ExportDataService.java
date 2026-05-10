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
        List<KodikEpisodeVariant> variants = episodeVariantRepository.findByContentId(contentId);

        List<KodikEpisodeVariant> decodedVariants =
                variants.stream()
                        .filter(v -> v.getMp4Link() != null && !v.getMp4Link().isBlank())
                        .toList();

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

        List<ContentExportDto> exportData =
                readyContent.stream()
                        .map(
                                content -> {
                                    List<KodikEpisodeVariant> variants =
                                            episodeVariantRepository
                                                    .findByContentId(content.getId())
                                                    .stream()
                                                    .filter(
                                                            v ->
                                                                    v.getMp4Link() != null
                                                                            && !v.getMp4Link()
                                                                                    .isBlank())
                                                    .toList();
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
     * SourceCatalogEvent}s for direct consumption by Kin's {@code external-bridge} (and any
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
                            List<KodikEpisodeVariant> variants =
                                    episodeVariantRepository.findByContentId(content.getId());
                            return SourceEventMapper.toEvent(content, variants, clock);
                        })
                .toList();
    }
}
