package com.dailylanguage.persistence;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedJdbcTypes;
import org.apache.ibatis.type.MappedTypes;

@MappedTypes(UUID.class)
@MappedJdbcTypes(JdbcType.OTHER)
//MyBatis 3.5.19 没有内建的 UUIDTypeHandler。MyBatis 无法确定如何处理 XML resultMap 中的 java.util.UUID
public final class PostgreSqlUuidTypeHandler extends BaseTypeHandler<UUID> {

    @Override
    public void setNonNullParameter(
            PreparedStatement statement,
            int parameterIndex,
            UUID parameter,
            JdbcType jdbcType) throws SQLException {
        statement.setObject(parameterIndex, parameter);
    }

    @Override
    public UUID getNullableResult(ResultSet resultSet, String columnName) throws SQLException {
        return resultSet.getObject(columnName, UUID.class);
    }

    @Override
    public UUID getNullableResult(ResultSet resultSet, int columnIndex) throws SQLException {
        return resultSet.getObject(columnIndex, UUID.class);
    }

    @Override
    public UUID getNullableResult(CallableStatement statement, int columnIndex) throws SQLException {
        return statement.getObject(columnIndex, UUID.class);
    }
}
