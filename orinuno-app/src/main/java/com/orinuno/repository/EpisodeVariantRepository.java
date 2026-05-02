package com.orinuno.repository;

import com.orinuno.model.KodikEpisodeVariant;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface EpisodeVariantRepository {

    Optional<KodikEpisodeVariant> findById(@Param("id") Long id);

    List<KodikEpisodeVariant> findByContentId(@Param("contentId") Long contentId);

    List<KodikEpisodeVariant> findByContentIdWithoutMp4(@Param("contentId") Long contentId);

    void insert(KodikEpisodeVariant variant);

    void upsertWithCoalesce(KodikEpisodeVariant variant);

    void batchUpsertWithCoalesce(@Param("list") List<KodikEpisodeVariant> variants);

    void updateMp4Link(@Param("id") Long id, @Param("mp4Link") String mp4Link);

    void updateLocalFilepath(@Param("id") Long id, @Param("localFilepath") String localFilepath);

    List<KodikEpisodeVariant> findExpiredLinks(
            @Param("hoursThreshold") int hoursThreshold, @Param("limit") int limit);

    List<KodikEpisodeVariant> findFailedDecode(@Param("limit") int limit);

    List<KodikEpisodeVariant> findDownloaded(@Param("limit") int limit);

    void deleteByContentId(@Param("contentId") Long contentId);
}
