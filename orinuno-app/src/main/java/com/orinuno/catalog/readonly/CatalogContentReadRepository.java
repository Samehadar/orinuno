/*
 * CatalogContentReadRepository — ADR 0018 Phase 5.4 skeleton.
 *
 * SELECT-only access into the shared catalog DB owned by `meter`. Today this
 * bean is unused — Phase 5.7 lights up CatalogController against it. Kept as a
 * minimal vertical slice (one lookup) so the wiring + tests are in place when
 * Phase 5.7 lands.
 *
 * The repository must ONLY issue SELECTs. Production runs with a DB user that
 * has SELECT-only grants (Phase 5.9), making accidental writes fail at the
 * MySQL layer too, but the discipline is encoded here first.
 */
package com.orinuno.catalog.readonly;

import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
@ConditionalOnBean(name = "catalogReadJdbcTemplate")
public class CatalogContentReadRepository {

    private static final String FIND_BY_ID =
            "SELECT id, title_ru, title_en, kind, year, shikimori_id, mal_id, imdb_id,"
                    + " kinopoisk_id, mdl_id, tmdb_id, created_at, updated_at"
                    + " FROM catalog_content WHERE id = ?";

    /**
     * ADR 0021 §E2 — lookup canonical content_id by Kinopoisk external id. Used by
     * MultiSourceController.rankedByKinopoiskId after the orinuno-app KodikContent /
     * ContentRepository surface retired. Hits the denormalised kinopoisk_id column on
     * catalog_content (kept in sync by CatalogIdentityResolver alongside the bindings
     * in catalog_content_external_id).
     */
    private static final String FIND_ID_BY_KINOPOISK_ID =
            "SELECT id FROM catalog_content WHERE kinopoisk_id = ? LIMIT 1";

    private static final RowMapper<CatalogContentRow> ROW_MAPPER =
            (rs, rowNum) ->
                    new CatalogContentRow(
                            rs.getLong("id"),
                            rs.getString("title_ru"),
                            rs.getString("title_en"),
                            rs.getString("kind"),
                            getNullableInt(rs, "year"),
                            rs.getString("shikimori_id"),
                            rs.getString("mal_id"),
                            rs.getString("imdb_id"),
                            rs.getString("kinopoisk_id"),
                            rs.getString("mdl_id"),
                            rs.getString("tmdb_id"),
                            toLocalDateTime(rs.getTimestamp("created_at")),
                            toLocalDateTime(rs.getTimestamp("updated_at")));

    private final JdbcTemplate jdbc;

    public CatalogContentReadRepository(
            @Qualifier("catalogReadJdbcTemplate") JdbcTemplate catalogReadJdbcTemplate) {
        this.jdbc = catalogReadJdbcTemplate;
    }

    public Optional<CatalogContentRow> findById(long id) {
        return jdbc.query(FIND_BY_ID, ROW_MAPPER, id).stream().findFirst();
    }

    /**
     * Resolve the canonical content_id for a Kinopoisk external id. Returns empty when no row
     * carries this kinopoisk_id (binding not in catalog_content_external_id, or no Kodik
     * SourceCatalogEvent observed this title yet).
     */
    public Optional<Long> findIdByKinopoiskId(String kinopoiskId) {
        if (kinopoiskId == null || kinopoiskId.isBlank()) {
            return Optional.empty();
        }
        return jdbc
                .query(FIND_ID_BY_KINOPOISK_ID, (rs, rowNum) -> rs.getLong("id"), kinopoiskId)
                .stream()
                .findFirst();
    }

    private static Integer getNullableInt(java.sql.ResultSet rs, String col)
            throws java.sql.SQLException {
        int v = rs.getInt(col);
        return rs.wasNull() ? null : v;
    }

    private static LocalDateTime toLocalDateTime(java.sql.Timestamp ts) {
        return ts == null ? null : ts.toLocalDateTime();
    }
}
