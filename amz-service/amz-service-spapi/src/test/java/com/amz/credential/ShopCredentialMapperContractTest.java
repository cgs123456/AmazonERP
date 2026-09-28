package com.amz.credential;

import com.amz.mapper.ShopCredentialMapper;
import com.amz.model.ShopCredentialEntity;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 凭证 Mapper 的数据库原子性契约。
 * <p>
 * Java 侧预计算版本会在并发写入时丢失更新；版本递增必须留在单条 SQL 中。
 */
class ShopCredentialMapperContractTest {

    @Test
    void casUpdateIncrementsVersionAndChecksExpectedVersionInSql() throws Exception {
        Method method = ShopCredentialMapper.class.getMethod(
                "updateIfVersion", ShopCredentialEntity.class, long.class);
        String sql = normalizedSql(method);

        assertTrue(sql.contains("version = version + 1"),
                "CAS 更新必须在 SQL 中原子递增 version: " + sql);
        assertTrue(sql.contains("and version = #{expectedversion}"),
                "CAS 更新必须把 expectedVersion 放入 WHERE，不能无条件覆盖: " + sql);
    }

    @Test
    void unconditionalUpdateAlsoIncrementsVersionAtomicallyInSql() throws Exception {
        Method method = ShopCredentialMapper.class.getMethod(
                "updateUnconditionally", ShopCredentialEntity.class);
        String sql = normalizedSql(method);

        assertTrue(sql.contains("version = version + 1"),
                "无条件覆盖也必须由 SQL 原子递增 version，避免缓存与 DB 版本分叉: " + sql);
    }

    private static String normalizedSql(Method method) {
        Update update = method.getAnnotation(Update.class);
        if (update == null || update.value().length == 0) {
            throw new AssertionError("缺少 @Update SQL: " + method.getName());
        }
        return String.join(" ", update.value())
                .replaceAll("\\s+", " ")
                .trim()
                .toLowerCase(Locale.ROOT);
    }
}
