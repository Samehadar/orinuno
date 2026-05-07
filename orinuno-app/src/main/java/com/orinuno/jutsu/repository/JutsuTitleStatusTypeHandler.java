package com.orinuno.jutsu.repository;

import com.orinuno.jutsu.model.JutsuTitleStatus;
import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedTypes;

/**
 * MyBatis bridge between {@link JutsuTitleStatus} (UPPERCASE Java constants) and the lowercase
 * MySQL enum values ({@code 'ongoing'}, {@code 'released'}). The default {@code EnumTypeHandler}
 * calls {@link Enum#valueOf(Class, String)} which is case-sensitive and would blow up on every
 * read.
 */
@MappedTypes(JutsuTitleStatus.class)
public class JutsuTitleStatusTypeHandler extends BaseTypeHandler<JutsuTitleStatus> {

    @Override
    public void setNonNullParameter(
            PreparedStatement ps, int i, JutsuTitleStatus parameter, JdbcType jdbcType)
            throws SQLException {
        ps.setString(i, parameter.dbValue());
    }

    @Override
    public JutsuTitleStatus getNullableResult(ResultSet rs, String columnName) throws SQLException {
        return JutsuTitleStatus.fromDbValue(rs.getString(columnName));
    }

    @Override
    public JutsuTitleStatus getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        return JutsuTitleStatus.fromDbValue(rs.getString(columnIndex));
    }

    @Override
    public JutsuTitleStatus getNullableResult(CallableStatement cs, int columnIndex)
            throws SQLException {
        return JutsuTitleStatus.fromDbValue(cs.getString(columnIndex));
    }
}
