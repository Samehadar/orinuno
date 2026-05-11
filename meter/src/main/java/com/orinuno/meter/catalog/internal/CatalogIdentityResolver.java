package com.orinuno.meter.catalog.internal;

import com.orinuno.meter.catalog.api.CatalogIdentityRequest;
import com.orinuno.meter.catalog.api.CatalogPublicApi;
import com.orinuno.meter.catalog.model.CatalogContent;
import com.orinuno.meter.catalog.model.CatalogContentExternalId;
import com.orinuno.meter.catalog.model.CatalogContentKind;
import com.orinuno.meter.catalog.model.CatalogSourceType;
import com.orinuno.meter.catalog.repository.CatalogContentExternalIdRepository;
import com.orinuno.meter.catalog.repository.CatalogContentRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Identity resolver for the L3 universal catalog (ARCH-0016 P1b Step 1.B). Sole implementor of
 * {@link CatalogPublicApi} — the only class in {@code com.orinuno.catalog.internal} that other
 * bounded contexts ({@code kodik}, {@code jutsu}, future {@code sibnet}/{@code aniboom}) ever
 * reach, and only through the interface, never the concrete class.
 *
 * <p>Algorithm summary:
 *
 * <ol>
 *   <li><strong>Anchor lookup.</strong> Walk the priority order shikimori → mal → imdb → kinopoisk
 *       → mdl → tmdb. For each external-database id present in the request, hit the dedicated
 *       identity-column index on {@code catalog_content}. First match becomes the anchor.
 *   <li><strong>Source-context fallback.</strong> If no external-db id matched, try the binding on
 *       {@code (request.sourceType, request.sourceId)} via {@code catalog_content_external_id}.
 *   <li><strong>Insert if missing.</strong> If still no anchor, INSERT a fresh canonical row.
 *       Identity columns and chrome (titleRu/titleEn/kind/year) come straight from the request.
 *   <li><strong>Chrome backfill.</strong> If anchor was found and the request carries chrome the
 *       anchor doesn't have, COALESCE-protected UPDATE fills the missing columns. The first writer
 *       wins — once a column has a value the resolver never overwrites it.
 *   <li><strong>Binding attachment.</strong> For every external id in the request (including the
 *       source-context one), attach a {@code catalog_content_external_id} row pointing at the
 *       anchor. Bindings already pointing at a <em>different</em> canonical row are left alone and
 *       logged at {@code WARN} — auto-merge of two canonical rows is deliberately deferred (see
 *       {@code TECH_DEBT.md}).
 *   <li><strong>Identity-column promotion.</strong> When attaching an external-database binding
 *       whose identity column on the anchor is null, the resolver writes the new value. This keeps
 *       the denormalised {@code catalog_content} columns in sync with the normalised {@code
 *       catalog_content_external_id} table inside the same transaction.
 * </ol>
 *
 * <p>{@link #findOrCreateContent(CatalogIdentityRequest)} is {@code @Transactional}. Callers (sync
 * workers in {@code jutsu} / {@code kodik}) wire this method into their per-row upsert transaction
 * so a crash mid-resolution leaves the canonical row consistent (either fully materialised + bound,
 * or absent and retried on the next worker tick).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CatalogIdentityResolver implements CatalogPublicApi {

    /**
     * External-database lookup priority. shikimori first because the open-source consumer base is
     * anime-leaning and Shikimori has the densest coverage; tmdb last because it overlaps with the
     * more authoritative kinopoisk/imdb on titles we care about.
     */
    private static final List<CatalogSourceType> EXTERNAL_DB_LOOKUP_ORDER =
            List.of(
                    CatalogSourceType.SHIKIMORI,
                    CatalogSourceType.MAL,
                    CatalogSourceType.IMDB,
                    CatalogSourceType.KINOPOISK,
                    CatalogSourceType.MDL,
                    CatalogSourceType.TMDB);

    private final CatalogContentRepository contentRepository;
    private final CatalogContentExternalIdRepository externalIdRepository;
    private final Clock clock;

    @Override
    @Transactional
    public CatalogContent findOrCreateContent(CatalogIdentityRequest request) {
        CatalogContent anchor = lookupAnchor(request).orElseGet(() -> insertFreshContent(request));
        backfillChrome(anchor, request);
        attachAllBindings(anchor, request);
        return contentRepository.findById(anchor.getId()).orElse(anchor);
    }

    @Override
    @Transactional
    public CatalogContentExternalId attachExternalId(
            long contentId, CatalogSourceType sourceType, String externalId) {
        if (sourceType == null || externalId == null || externalId.isBlank()) {
            throw new IllegalArgumentException(
                    "sourceType and externalId must be present for attachExternalId");
        }
        Optional<CatalogContentExternalId> existing =
                externalIdRepository.findByExternalId(sourceType, externalId.trim());
        if (existing.isPresent()) {
            CatalogContentExternalId binding = existing.get();
            if (!Long.valueOf(contentId).equals(binding.getContentId())) {
                log.warn(
                        "catalog: external-id conflict — {}:{} is already bound to canonical "
                                + "content {}, requested attach to {}; first writer wins, "
                                + "binding left untouched",
                        sourceType.wire(),
                        externalId,
                        binding.getContentId(),
                        contentId);
            }
            return binding;
        }
        CatalogContentExternalId fresh =
                CatalogContentExternalId.builder()
                        .contentId(contentId)
                        .sourceType(sourceType)
                        .externalId(externalId.trim())
                        .createdAt(now())
                        .build();
        externalIdRepository.insert(fresh);
        if (sourceType.isExternalDatabase()) {
            promoteIdentityColumnIfNull(contentId, sourceType, externalId.trim());
        }
        return fresh;
    }

    @Override
    public Optional<CatalogContent> findContentById(long contentId) {
        return contentRepository.findById(contentId);
    }

    @Override
    public Optional<CatalogContent> findContentByExternalId(
            CatalogSourceType sourceType, String externalId) {
        if (sourceType == null || externalId == null || externalId.isBlank()) {
            return Optional.empty();
        }
        return externalIdRepository
                .findByExternalId(sourceType, externalId.trim())
                .flatMap(binding -> contentRepository.findById(binding.getContentId()));
    }

    @Override
    public List<CatalogContentExternalId> findAttachedExternalIds(long contentId) {
        return externalIdRepository.findByContentId(contentId);
    }

    // ─── internals ───────────────────────────────────────────────────────────

    private Optional<CatalogContent> lookupAnchor(CatalogIdentityRequest request) {
        for (CatalogSourceType type : EXTERNAL_DB_LOOKUP_ORDER) {
            Optional<String> id = request.externalId(type);
            if (id.isEmpty()) {
                continue;
            }
            Optional<CatalogContent> hit = lookupByExternalDb(type, id.get());
            if (hit.isPresent()) {
                return hit;
            }
        }
        return externalIdRepository
                .findByExternalId(request.sourceType(), request.sourceId())
                .flatMap(binding -> contentRepository.findById(binding.getContentId()));
    }

    private Optional<CatalogContent> lookupByExternalDb(CatalogSourceType type, String externalId) {
        return switch (type) {
            case SHIKIMORI -> contentRepository.findByShikimoriId(externalId);
            case MAL -> contentRepository.findByMalId(externalId);
            case IMDB -> contentRepository.findByImdbId(externalId);
            case KINOPOISK -> contentRepository.findByKinopoiskId(externalId);
            case MDL -> contentRepository.findByMdlId(externalId);
            case TMDB -> contentRepository.findByTmdbId(externalId);
            case KODIK, JUTSU ->
                    throw new IllegalStateException(
                            "lookupByExternalDb called with non-external-db type " + type);
        };
    }

    private CatalogContent insertFreshContent(CatalogIdentityRequest request) {
        LocalDateTime now = now();
        CatalogContent fresh =
                CatalogContent.builder()
                        .titleRu(request.titleRu())
                        .titleEn(request.titleEn())
                        .kind(request.kind() == null ? CatalogContentKind.UNKNOWN : request.kind())
                        .year(request.year())
                        .shikimoriId(request.externalId(CatalogSourceType.SHIKIMORI).orElse(null))
                        .malId(request.externalId(CatalogSourceType.MAL).orElse(null))
                        .imdbId(request.externalId(CatalogSourceType.IMDB).orElse(null))
                        .kinopoiskId(request.externalId(CatalogSourceType.KINOPOISK).orElse(null))
                        .mdlId(request.externalId(CatalogSourceType.MDL).orElse(null))
                        .tmdbId(request.externalId(CatalogSourceType.TMDB).orElse(null))
                        .createdAt(now)
                        .updatedAt(now)
                        .build();
        contentRepository.insert(fresh);
        log.debug(
                "catalog: inserted canonical row id={} for {}:{}",
                fresh.getId(),
                request.sourceType().wire(),
                request.sourceId());
        return fresh;
    }

    private void backfillChrome(CatalogContent anchor, CatalogIdentityRequest request) {
        boolean nothingToDo =
                request.titleRu() == null
                        && request.titleEn() == null
                        && request.kind() == null
                        && request.year() == null;
        if (nothingToDo) {
            return;
        }
        CatalogContent patch =
                CatalogContent.builder()
                        .id(anchor.getId())
                        .titleRu(anchor.getTitleRu() == null ? request.titleRu() : null)
                        .titleEn(anchor.getTitleEn() == null ? request.titleEn() : null)
                        .kind(
                                anchor.getKind() == null
                                                || anchor.getKind() == CatalogContentKind.UNKNOWN
                                        ? request.kind()
                                        : null)
                        .year(anchor.getYear() == null ? request.year() : null)
                        .updatedAt(now())
                        .build();
        if (patch.getTitleRu() == null
                && patch.getTitleEn() == null
                && patch.getKind() == null
                && patch.getYear() == null) {
            return;
        }
        contentRepository.update(patch);
    }

    /**
     * Attach every external id from the request — both the (sourceType, sourceId) primary anchor
     * and every external-database id — as bindings in {@code catalog_content_external_id}.
     * External-database bindings that are net-new also promote the corresponding identity column on
     * the anchor, so the denormalised hot path stays consistent.
     */
    private void attachAllBindings(CatalogContent anchor, CatalogIdentityRequest request) {
        // The source-context binding (KODIK / JUTSU + sourceId) is always attached.
        attachOneBinding(anchor, request.sourceType(), request.sourceId());

        // External-database bindings.
        Map<CatalogSourceType, String> externalIds = new EnumMap<>(CatalogSourceType.class);
        externalIds.putAll(request.externalIds());
        for (Map.Entry<CatalogSourceType, String> e : externalIds.entrySet()) {
            attachOneBinding(anchor, e.getKey(), e.getValue());
        }
    }

    private void attachOneBinding(
            CatalogContent anchor, CatalogSourceType type, String externalId) {
        Optional<CatalogContentExternalId> existing =
                externalIdRepository.findByExternalId(type, externalId);
        if (existing.isPresent()) {
            CatalogContentExternalId binding = existing.get();
            if (!anchor.getId().equals(binding.getContentId())) {
                log.warn(
                        "catalog: external-id conflict — {}:{} is already bound to canonical "
                                + "content {}, expected anchor {}; first writer wins, binding "
                                + "left untouched (auto-merge deferred — see TECH_DEBT)",
                        type.wire(),
                        externalId,
                        binding.getContentId(),
                        anchor.getId());
            }
            return;
        }
        externalIdRepository.insert(
                CatalogContentExternalId.builder()
                        .contentId(anchor.getId())
                        .sourceType(type)
                        .externalId(externalId)
                        .createdAt(now())
                        .build());
        if (type.isExternalDatabase()) {
            promoteIdentityColumnIfNull(anchor.getId(), type, externalId);
        }
    }

    /**
     * Write the new external-database id into the anchor's identity column iff that column is
     * currently null. The anchor was inserted with the request's external ids on the fresh-row path
     * so the column is usually populated already; this branch matters when the anchor was resolved
     * by a different identity column and the new external-db id is genuinely new.
     */
    private void promoteIdentityColumnIfNull(
            long contentId, CatalogSourceType type, String externalId) {
        CatalogContent anchor = contentRepository.findById(contentId).orElse(null);
        if (anchor == null) {
            return;
        }
        boolean alreadyHasValue =
                switch (type) {
                    case SHIKIMORI -> anchor.getShikimoriId() != null;
                    case MAL -> anchor.getMalId() != null;
                    case IMDB -> anchor.getImdbId() != null;
                    case KINOPOISK -> anchor.getKinopoiskId() != null;
                    case MDL -> anchor.getMdlId() != null;
                    case TMDB -> anchor.getTmdbId() != null;
                    case KODIK, JUTSU -> true;
                };
        if (alreadyHasValue) {
            return;
        }
        CatalogContent patch =
                CatalogContent.builder()
                        .id(contentId)
                        .updatedAt(now())
                        .shikimoriId(type == CatalogSourceType.SHIKIMORI ? externalId : null)
                        .malId(type == CatalogSourceType.MAL ? externalId : null)
                        .imdbId(type == CatalogSourceType.IMDB ? externalId : null)
                        .kinopoiskId(type == CatalogSourceType.KINOPOISK ? externalId : null)
                        .mdlId(type == CatalogSourceType.MDL ? externalId : null)
                        .tmdbId(type == CatalogSourceType.TMDB ? externalId : null)
                        .build();
        contentRepository.update(patch);
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }
}
