package com.orinuno.meter.catalog.repository;

import com.orinuno.meter.catalog.model.CatalogContentExternalId;
import com.orinuno.meter.catalog.model.CatalogSourceType;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * MyBatis mapper for {@code catalog_content_external_id} (ARCH-0016 P1b — normalised external-id
 * attachments).
 *
 * <p>{@code (sourceType, externalId)} is unique. The {@link #insertIfAbsent} method is the primary
 * write path used by {@code CatalogIdentityResolver}: it short-circuits when the binding already
 * points at the same content, returns an existing-binding marker when it points at a different
 * content (so the resolver can decide to merge), and otherwise inserts.
 *
 * <p>The {@code source_type} wire form is the lowercase enum name (see {@link
 * CatalogSourceType#wire()}). Mapper resolves the enum on read via a {@link
 * com.orinuno.catalog.repository.CatalogSourceTypeTypeHandler typed handler}.
 */
@Mapper
public interface CatalogContentExternalIdRepository {

    Optional<CatalogContentExternalId> findByExternalId(
            @Param("sourceType") CatalogSourceType sourceType,
            @Param("externalId") String externalId);

    List<CatalogContentExternalId> findByContentId(@Param("contentId") long contentId);

    List<CatalogContentExternalId> findByContentIdAndSource(
            @Param("contentId") long contentId, @Param("sourceType") CatalogSourceType sourceType);

    /**
     * Append-only insert. Caller is responsible for checking {@link
     * #findByExternalId(CatalogSourceType, String)} first — the unique index will throw on
     * duplicate insert. The mapper populates the auto-generated id back onto the argument.
     */
    void insert(@Param("link") CatalogContentExternalId link);

    /**
     * Re-point an existing {@code (sourceType, externalId)} row to a different canonical content.
     * Used by the resolver during merges. Returns the number of affected rows (0 means the binding
     * wasn't there).
     */
    int reassignContent(
            @Param("sourceType") CatalogSourceType sourceType,
            @Param("externalId") String externalId,
            @Param("contentId") long newContentId);

    int deleteByContentId(@Param("contentId") long contentId);

    long count();
}
