package com.amz.config;

import com.amz.util.CryptoUtil;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * MyBatis TypeHandler：持久化时自动 AES-256-GCM 加密，读取时自动解密。
 * <p>
 * 实现了 {@link org.apache.ibatis.type.TypeHandler}{@code <String>}，
 * 通过 {@link BaseTypeHandler} 处理 null 判定后委托给以下方法：
 * <ul>
 *   <li>{@link #setNonNullParameter}：写入数据库前调用 {@link CryptoUtil#encrypt(String)}</li>
 *   <li>{@link #getNullableResult}：从数据库读出后调用 {@link CryptoUtil#decrypt(String)}</li>
 * </ul>
 * <p>
 * 使用方式：在实体类字段上声明
 * <pre>{@code
 *   @TableField(value = "spapi_refresh_token", typeHandler = CryptoTypeHandler.class)
 *   private String spapiRefreshToken;
 * }</pre>
 * 并在 {@code @TableName} 上设置 {@code autoResultMap = true}，
 * 以保证 SELECT 查询也走 TypeHandler（否则仅 INSERT/UPDATE 生效）。
 * <p>
 * 本类不由 Spring 容器管理，MyBatis 通过默认构造函数实例化，
 * 加解密依赖静态单例 {@link CryptoUtil#getInstance()}。
 */
public class CryptoTypeHandler extends BaseTypeHandler<String> {

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, String parameter, JdbcType jdbcType)
            throws SQLException {
        ps.setString(i, CryptoUtil.getInstance().encrypt(parameter));
    }

    @Override
    public String getNullableResult(ResultSet rs, String columnName) throws SQLException {
        String value = rs.getString(columnName);
        return value == null ? null : CryptoUtil.getInstance().decrypt(value);
    }

    @Override
    public String getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        String value = rs.getString(columnIndex);
        return value == null ? null : CryptoUtil.getInstance().decrypt(value);
    }

    @Override
    public String getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        String value = cs.getString(columnIndex);
        return value == null ? null : CryptoUtil.getInstance().decrypt(value);
    }
}
