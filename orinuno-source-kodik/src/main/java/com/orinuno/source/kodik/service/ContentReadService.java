/*
 * ContentReadService — ADR 0021 §C1.1.
 *
 * Read half of the legacy orinuno-app ContentService, ported into the
 * source-kodik bounded context. Backs GET /api/v1/content/* against the
 * orinuno_source_kodik schema. The write half (findOrCreateContent +
 * saveVariants) stays in orinuno-app for now — those callers
 * (ParserService + KodikDumpBootstrapService) migrate as part of
 * Block D (parse slice + dumps slice).
 */
package com.orinuno.source.kodik.service;

import com.orinuno.source.kodik.mapper.ContentDtoMapper;
import com.orinuno.source.kodik.model.dto.ContentDto;
import com.orinuno.source.kodik.model.dto.EpisodeVariantDto;
import com.orinuno.source.kodik.model.dto.PageRequest;
import com.orinuno.source.kodik.model.dto.PageResponse;
import com.orinuno.source.kodik.repository.ContentRepository;
import com.orinuno.source.kodik.repository.EpisodeVariantRepository;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ContentReadService {

    private final ContentRepository contentRepository;
    private final EpisodeVariantRepository episodeVariantRepository;

    public Optional<ContentDto> findById(Long id) {
        return contentRepository.findById(id).map(ContentDtoMapper::toDto);
    }

    public Optional<ContentDto> findByKinopoiskId(String kinopoiskId) {
        return contentRepository.findByKinopoiskId(kinopoiskId).map(ContentDtoMapper::toDto);
    }

    public PageResponse<ContentDto> findAll(PageRequest pageRequest) {
        int offset = pageRequest.getPage() * pageRequest.getSize();
        List<ContentDto> content =
                contentRepository
                        .findAll(
                                offset,
                                pageRequest.getSize(),
                                pageRequest.getSortBy(),
                                pageRequest.getOrder())
                        .stream()
                        .map(ContentDtoMapper::toDto)
                        .toList();

        long total = contentRepository.count();
        int totalPages = (int) Math.ceil((double) total / pageRequest.getSize());

        return PageResponse.<ContentDto>builder()
                .content(content)
                .page(pageRequest.getPage())
                .size(pageRequest.getSize())
                .totalElements(total)
                .totalPages(totalPages)
                .build();
    }

    public List<EpisodeVariantDto> findVariantsByContentId(Long contentId) {
        return episodeVariantRepository.findByContentId(contentId).stream()
                .map(ContentDtoMapper::toDto)
                .toList();
    }
}
