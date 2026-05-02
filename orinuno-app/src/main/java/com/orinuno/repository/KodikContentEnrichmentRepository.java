package com.orinuno.repository;

import com.orinuno.model.KodikContentEnrichment;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** META-1 (ADR 0010) — MyBatis mapper for {@code kodik_content_enrichment}. */
@Mapper
public interface KodikContentEnrichmentRepository {

    Optional<KodikContentEnrichment> findByContentAndSource(
            @Param("contentId") Long contentId, @Param("source") String source);

    List<KodikContentEnrichment> findByContent(@Param("contentId") Long contentId);

    /**
     * Find content rows whose enrichment for the given source is missing or older than {@code
     * olderThan}.
     */
    List<Long> findContentIdsNeedingRefresh(
            @Param("source") String source,
            @Param("olderThan") LocalDateTime olderThan,
            @Param("limit") int limit);

    void upsert(@Param("row") KodikContentEnrichment row);
}
