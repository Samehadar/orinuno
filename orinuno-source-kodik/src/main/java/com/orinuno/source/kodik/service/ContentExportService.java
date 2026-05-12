/*
 * ContentExportService — ADR 0021 §C4.1.
 *
 * Port of orinuno-app's ExportDataService (getExportData +
 * getReadyForExport) into source-kodik. Backs GET /api/v1/export/* against
 * the orinuno_source_kodik schema. The third method on the legacy
 * ExportDataService, findReadyForExportAsEvents, is intentionally not
 * ported — source-kodik already exposes a canonical
 * SourceCatalogEvent stream via KodikSourceEventProjection +
 * KodikSourceEventMapper, which is what meter polls (ADR 0018 Phase 5.5).
 */
package com.orinuno.source.kodik.service;

import com.orinuno.source.kodik.mapper.ContentDtoMapper;
import com.orinuno.source.kodik.model.KodikContent;
import com.orinuno.source.kodik.model.KodikEpisodeVariant;
import com.orinuno.source.kodik.model.dto.ContentExportDto;
import com.orinuno.source.kodik.model.dto.PageRequest;
import com.orinuno.source.kodik.model.dto.PageResponse;
import com.orinuno.source.kodik.repository.ContentRepository;
import com.orinuno.source.kodik.repository.EpisodeVariantRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class ContentExportService {

    private final ContentRepository contentRepository;
    private final EpisodeVariantRepository episodeVariantRepository;

    public Optional<ContentExportDto> getExportData(Long contentId) {
        Optional<KodikContent> contentOpt = contentRepository.findById(contentId);
        if (contentOpt.isEmpty()) {
            return Optional.empty();
        }
        KodikContent content = contentOpt.get();
        List<KodikEpisodeVariant> decodedVariants =
                episodeVariantRepository.findByContentIdWithDecodedVideo(contentId);
        if (decodedVariants.isEmpty()) {
            log.debug("Content id={} has no decoded variants yet", contentId);
        }
        return Optional.of(ContentDtoMapper.toExportDto(content, decodedVariants));
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
                                                    .findByContentIdWithDecodedVideo(
                                                            content.getId());
                                    return ContentDtoMapper.toExportDto(content, variants);
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
}
