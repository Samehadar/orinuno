package com.orinuno.repository;

import com.orinuno.model.KodikContent;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface ContentRepository {

    Optional<KodikContent> findById(@Param("id") Long id);

    Optional<KodikContent> findByKinopoiskId(@Param("kinopoiskId") String kinopoiskId);

    Optional<KodikContent> findByTitleAndYear(
            @Param("title") String title, @Param("year") Integer year);

    List<KodikContent> findAll(
            @Param("offset") int offset,
            @Param("limit") int limit,
            @Param("sortBy") String sortBy,
            @Param("order") String order);

    List<KodikContent> findReadyForExport(
            @Param("offset") int offset,
            @Param("limit") int limit,
            @Param("updatedSince") LocalDateTime updatedSince);

    long count();

    long countReadyForExport(@Param("updatedSince") LocalDateTime updatedSince);

    void insert(KodikContent content);

    void update(KodikContent content);

    void upsertByKinopoiskId(KodikContent content);

    void deleteById(@Param("id") Long id);

    /**
     * Narrow batch lookup used by the calendar enrichment path (IDEA-AP-5). Returns one row per
     * matched {@code shikimori_id} with the columns {@code id} and {@code shikimoriId} only — no
     * full {@link KodikContent} rehydration so we avoid materialising heavy {@code material_data}
     * blobs for an O(N) sidebar widget.
     */
    List<Map<String, Object>> findIdsByShikimoriIds(
            @Param("shikimoriIds") List<String> shikimoriIds);
}
