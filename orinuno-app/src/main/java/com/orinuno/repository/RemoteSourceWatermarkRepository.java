package com.orinuno.repository;

import com.orinuno.model.RemoteSourceWatermark;
import java.time.LocalDateTime;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * MyBatis mapper for {@code orinuno_remote_source_watermark} (ADR 0018 Phase 2.11). Backing store
 * for the {@code *RemoteEventPoller} beans that poll standalone per-source services. Idempotent
 * upsert keyed on {@code source_type}.
 */
@Mapper
public interface RemoteSourceWatermarkRepository {

    Optional<RemoteSourceWatermark> findBySourceType(@Param("sourceType") String sourceType);

    void upsert(
            @Param("sourceType") String sourceType,
            @Param("lastFetchedAt") LocalDateTime lastFetchedAt,
            @Param("lastPolledAt") LocalDateTime lastPolledAt,
            @Param("lastEventCount") int lastEventCount,
            @Param("lastError") String lastError);
}
