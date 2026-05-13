/*
 * CatalogEpisodeSourceReadRepository — ADR 0021 §C1.4.
 *
 * SELECT-only access to episode_source rows in the shared catalog DB
 * (orinuno_catalog, owned by meter). Replaces orinuno-app's primary-DS
 * com.orinuno.repository.EpisodeSourceRepository on the read path —
 * MultiSourceController is the sole consumer after C1.4.
 *
 * Gated on the catalogReadJdbcTemplate bean (Phase 5.4 plumbing). In
 * monolith deploys without orinuno.catalog-read.url set, this bean is
 * absent and MultiSourceController degrades gracefully — see the
 * @ConditionalOnBean on the controller itself.
 */
package com.orinuno.catalog.readonly;

import com.orinuno.model.EpisodeSource;
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

// Gate matches CatalogReadDataSourceConfiguration's @ConditionalOnProperty so component
// scanning and the bean-factory post-processor evaluate against the same Environment
// property, not the bean-name registry. @ConditionalOnBean on @Repository was racy —
// worked by luck when more user beans existed; broke after Phase 2/5 shrank orinuno-app
// because the scan order shifted.
@Repository
@ConditionalOnProperty(prefix = "orinuno.catalog-read", name = "url")
public class CatalogEpisodeSourceReadRepository {

    private static final String FIND_BY_EPISODE =
            "SELECT id, content_id, season, episode, translator_id, translator_name,"
                    + " provider, source_url, source_type, discovered_at, last_seen_at"
                    + " FROM episode_source"
                    + " WHERE content_id = ? AND season = ? AND episode = ?";

    private static final RowMapper<EpisodeSource> ROW_MAPPER =
            CatalogEpisodeSourceReadRepository::mapRow;

    private final JdbcTemplate jdbc;

    public CatalogEpisodeSourceReadRepository(
            @Qualifier("catalogReadJdbcTemplate") JdbcTemplate catalogReadJdbcTemplate) {
        this.jdbc = catalogReadJdbcTemplate;
    }

    public List<EpisodeSource> findByEpisode(Long contentId, Integer season, Integer episode) {
        return jdbc.query(FIND_BY_EPISODE, ROW_MAPPER, contentId, season, episode);
    }

    private static EpisodeSource mapRow(ResultSet rs, int rowNum) throws SQLException {
        return EpisodeSource.builder()
                .id(rs.getLong("id"))
                .contentId(rs.getLong("content_id"))
                .season(rs.getInt("season"))
                .episode(rs.getInt("episode"))
                .translatorId(rs.getString("translator_id"))
                .translatorName(rs.getString("translator_name"))
                .provider(rs.getString("provider"))
                .sourceUrl(rs.getString("source_url"))
                .sourceType(rs.getString("source_type"))
                .discoveredAt(toLocalDateTime(rs.getTimestamp("discovered_at")))
                .lastSeenAt(toLocalDateTime(rs.getTimestamp("last_seen_at")))
                .build();
    }

    private static LocalDateTime toLocalDateTime(Timestamp ts) {
        return ts == null ? null : ts.toLocalDateTime();
    }
}
