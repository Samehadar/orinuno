package com.orinuno.catalog.repository;

import com.orinuno.catalog.model.CatalogSourceType;
import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedTypes;

/**
 * MyBatis {@link BaseTypeHandler} for the {@code source_type} column of {@code
 * catalog_content_external_id}. Persists the lowercase wire form ({@code kodik}, {@code shikimori},
 * …) and tolerates unknown values from the DB by returning {@code null} — so the resolver and reads
 * stay forward-compatible with a future source whose code wasn't deployed yet.
 */
@MappedTypes(CatalogSourceType.class)
public class CatalogSourceTypeTypeHandler extends BaseTypeHandler<CatalogSourceType> {

    @Override
    public void setNonNullParameter(
            PreparedStatement ps, int i, CatalogSourceType parameter, JdbcType jdbcType)
            throws SQLException {
        ps.setString(i, parameter.wire());
    }

    @Override
    public CatalogSourceType getNullableResult(ResultSet rs, String columnName)
            throws SQLException {
        return decode(rs.getString(columnName));
    }

    @Override
    public CatalogSourceType getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        return decode(rs.getString(columnIndex));
    }

    @Override
    public CatalogSourceType getNullableResult(CallableStatement cs, int columnIndex)
            throws SQLException {
        return decode(cs.getString(columnIndex));
    }

    private static CatalogSourceType decode(String value) {
        return CatalogSourceType.fromWire(value).orElse(null);
    }
}
