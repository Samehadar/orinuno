package com.orinuno.meter.catalog.repository;

import com.orinuno.meter.catalog.model.CatalogContentKind;
import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedTypes;

/**
 * MyBatis {@link BaseTypeHandler} for {@code catalog_content.kind}. Persists the lowercase wire
 * form ({@code movie}, {@code series}, {@code anime}). Unknown DB values surface as {@link
 * CatalogContentKind#UNKNOWN} so the catalog stays readable even after the schema vocabulary grows
 * past what the deployed code knows.
 */
@MappedTypes(CatalogContentKind.class)
public class CatalogContentKindTypeHandler extends BaseTypeHandler<CatalogContentKind> {

    @Override
    public void setNonNullParameter(
            PreparedStatement ps, int i, CatalogContentKind parameter, JdbcType jdbcType)
            throws SQLException {
        ps.setString(i, parameter.wire());
    }

    @Override
    public CatalogContentKind getNullableResult(ResultSet rs, String columnName)
            throws SQLException {
        String raw = rs.getString(columnName);
        return raw == null ? null : CatalogContentKind.fromWire(raw);
    }

    @Override
    public CatalogContentKind getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        String raw = rs.getString(columnIndex);
        return raw == null ? null : CatalogContentKind.fromWire(raw);
    }

    @Override
    public CatalogContentKind getNullableResult(CallableStatement cs, int columnIndex)
            throws SQLException {
        String raw = cs.getString(columnIndex);
        return raw == null ? null : CatalogContentKind.fromWire(raw);
    }
}
