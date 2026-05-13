/*
 * CatalogEpisodeVideoReadRepository — ADR 0021 §C1.4.
 *
 * SELECT-only access to episode_video rows in the shared catalog DB
 * (orinuno_catalog, owned by meter). Replaces orinuno-app's primary-DS
 * com.orinuno.repository.EpisodeVideoRepository on the read path.
 * MultiSourceController is the sole consumer after C1.4.
 */
package com.orinuno.catalog.readonly;

import com.orinuno.model.EpisodeVideo;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

// See CatalogEpisodeSourceReadRepository for why @ConditionalOnProperty replaces the
// fragile @ConditionalOnBean(name = "catalogReadJdbcTemplate").
@Repository
@ConditionalOnProperty(prefix = "orinuno.catalog-read", name = "url")
public class CatalogEpisodeVideoReadRepository {

    private static final String FIND_BY_SOURCE =
            "SELECT id, source_id, quality, video_url, video_format, decoded_at,"
                    + " decode_method, decode_failed_count, decode_last_error, ttl_seconds"
                    + " FROM episode_video"
                    + " WHERE source_id = ?";

    private static final RowMapper<EpisodeVideo> ROW_MAPPER =
            CatalogEpisodeVideoReadRepository::mapRow;

    private final JdbcTemplate jdbc;

    public CatalogEpisodeVideoReadRepository(
            @Qualifier("catalogReadJdbcTemplate") JdbcTemplate catalogReadJdbcTemplate) {
        this.jdbc = catalogReadJdbcTemplate;
    }

    public List<EpisodeVideo> findBySource(Long sourceId) {
        return jdbc.query(FIND_BY_SOURCE, ROW_MAPPER, sourceId);
    }

    private static EpisodeVideo mapRow(ResultSet rs, int rowNum) throws SQLException {
        return EpisodeVideo.builder()
                .id(rs.getLong("id"))
                .sourceId(rs.getLong("source_id"))
                .quality(rs.getString("quality"))
                .videoUrl(rs.getString("video_url"))
                .videoFormat(rs.getString("video_format"))
                .decodedAt(toLocalDateTime(rs.getTimestamp("decoded_at")))
                .decodeMethod(rs.getString("decode_method"))
                .decodeFailedCount(getNullableInt(rs, "decode_failed_count"))
                .decodeLastError(rs.getString("decode_last_error"))
                .ttlSeconds(getNullableInt(rs, "ttl_seconds"))
                .build();
    }

    private static Integer getNullableInt(ResultSet rs, String col) throws SQLException {
        int v = rs.getInt(col);
        return rs.wasNull() ? null : v;
    }

    private static LocalDateTime toLocalDateTime(Timestamp ts) {
        return ts == null ? null : ts.toLocalDateTime();
    }
}
