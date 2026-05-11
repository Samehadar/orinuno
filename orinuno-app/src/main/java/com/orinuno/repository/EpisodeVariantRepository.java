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

    /**
     * ADR 0018 Phase 0.4c — fetch a single variant joined with its decoded episode_video row.
     * Returns empty if no populated video_url exists for the variant. Populates {@code mp4Link},
     * {@code mp4LinkDecodedAt} and {@code decodeMethod} on the returned variant via column aliases
     * in the JOIN. Use this when a caller needs both the L1 row and the decoded URL — {@link
     * #findById(Long)} no longer carries those fields after the column drop.
     */
    Optional<KodikEpisodeVariant> findByIdWithDecodedVideo(@Param("id") Long id);

    /**
     * ADR 0018 Phase 0.4c — fetch every variant for a given content that has a populated decoded
     * video URL in episode_video. The returned variants carry {@code mp4Link} / {@code
     * mp4LinkDecodedAt} / {@code decodeMethod} populated from the joined columns; variants without
     * a successful decode are filtered out at the SQL level. Use for export and any other read that
     * needs the decoded URL alongside the L1 row.
     */
    List<KodikEpisodeVariant> findByContentIdWithDecodedVideo(@Param("contentId") Long contentId);

    void insert(KodikEpisodeVariant variant);

    void upsertWithCoalesce(KodikEpisodeVariant variant);

    void batchUpsertWithCoalesce(@Param("list") List<KodikEpisodeVariant> variants);

    void updateLocalFilepath(@Param("id") Long id, @Param("localFilepath") String localFilepath);

    List<KodikEpisodeVariant> findExpiredLinks(
            @Param("hoursThreshold") int hoursThreshold, @Param("limit") int limit);

    List<KodikEpisodeVariant> findFailedDecode(@Param("limit") int limit);

    List<KodikEpisodeVariant> findDownloaded(@Param("limit") int limit);

    void deleteByContentId(@Param("contentId") Long contentId);
}
