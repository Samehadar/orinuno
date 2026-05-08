package com.orinuno.service;

import com.orinuno.mapper.ContentMapper;
import com.orinuno.model.KodikContent;
import com.orinuno.model.KodikEpisodeVariant;
import com.orinuno.model.dto.ContentDto;
import com.orinuno.model.dto.EpisodeVariantDto;
import com.orinuno.model.dto.PageRequest;
import com.orinuno.model.dto.PageResponse;
import com.orinuno.repository.ContentRepository;
import com.orinuno.repository.EpisodeVariantRepository;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class ContentService {

    private final ContentRepository contentRepository;
    private final EpisodeVariantRepository episodeVariantRepository;
    private final KodikCatalogIngestion catalogIngestion;

    public Optional<ContentDto> findById(Long id) {
        return contentRepository.findById(id).map(ContentMapper::toDto);
    }

    public Optional<ContentDto> findByKinopoiskId(String kinopoiskId) {
        return contentRepository.findByKinopoiskId(kinopoiskId).map(ContentMapper::toDto);
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
                        .map(ContentMapper::toDto)
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
                .map(ContentMapper::toDto)
                .toList();
    }

    /**
     * PF7: Find or create content, with fallback grouping by (title, year) if kinopoisk_id is
     * absent.
     *
     * <p>After persistence, the row is fed to {@link KodikCatalogIngestion#ingest(KodikContent)}
     * (ARCH-0016 P1b Step 1.C.B) so the L3 universal catalog gets a synchronous binding for the
     * Kodik external ids. The bridge is gated by the {@code
     * orinuno.kodik.catalog-ingestion.enabled} kill-switch and isolates its failures internally, so
     * this method's contract is unchanged.
     */
    public KodikContent findOrCreateContent(KodikContent content) {
        if (content.getKinopoiskId() != null && !content.getKinopoiskId().isBlank()) {
            Optional<KodikContent> existing =
                    contentRepository.findByKinopoiskId(content.getKinopoiskId());
            if (existing.isPresent()) {
                KodikContent found = existing.get();
                content.setId(found.getId());
                contentRepository.update(content);
                catalogIngestion.ingest(content);
                return content;
            }
        } else {
            log.warn(
                    "⚠️ Content without kinopoisk_id: '{}' ({}). Using (title, year) fallback.",
                    content.getTitle(),
                    content.getYear());

            if (content.getTitle() != null && content.getYear() != null) {
                Optional<KodikContent> existing =
                        contentRepository.findByTitleAndYear(content.getTitle(), content.getYear());
                if (existing.isPresent()) {
                    KodikContent found = existing.get();
                    content.setId(found.getId());
                    contentRepository.update(content);
                    catalogIngestion.ingest(content);
                    return content;
                }
            }
        }

        contentRepository.insert(content);
        log.info("📝 Created new content: id={}, title='{}'", content.getId(), content.getTitle());
        catalogIngestion.ingest(content);
        return content;
    }

    public void saveVariants(List<KodikEpisodeVariant> variants) {
        if (variants.isEmpty()) return;
        for (KodikEpisodeVariant variant : variants) {
            episodeVariantRepository.upsertWithCoalesce(variant);
        }
        log.info(
                "💾 Saved {} episode variants for content_id={}",
                variants.size(),
                variants.get(0).getContentId());
    }
}
