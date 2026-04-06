package com.orinuno.repository;

import com.orinuno.model.KodikContent;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Mapper
public interface ContentRepository {

    Optional<KodikContent> findById(@Param("id") Long id);

    Optional<KodikContent> findByKinopoiskId(@Param("kinopoiskId") String kinopoiskId);

    Optional<KodikContent> findByTitleAndYear(@Param("title") String title, @Param("year") Integer year);

    List<KodikContent> findAll(@Param("offset") int offset,
                               @Param("limit") int limit,
                               @Param("sortBy") String sortBy,
                               @Param("order") String order);

    List<KodikContent> findReadyForExport(@Param("offset") int offset,
                                           @Param("limit") int limit,
                                           @Param("updatedSince") LocalDateTime updatedSince);

    long count();

    long countReadyForExport(@Param("updatedSince") LocalDateTime updatedSince);

    void insert(KodikContent content);

    void update(KodikContent content);

    void upsertByKinopoiskId(KodikContent content);

    void deleteById(@Param("id") Long id);
}
