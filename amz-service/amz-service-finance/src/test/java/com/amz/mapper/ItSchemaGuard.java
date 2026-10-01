package com.amz.mapper;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 手写 DDL 的 MySQL IT 的库名护栏。
 * <p>
 * 两个条件各自挡一类事故：
 * <ul>
 *   <li>{@code *_fwit} 是 {@code AllModulesFlywayMySqlIT} 对每个业务库 DROP + CREATE 的
 *       命名空间，手写建表的 IT 挤进同一个库，就会和它互相删表；</li>
 *   <li>等于环境变量里那个库名，则 IT 的 {@code DROP TABLE} 直接打在业务库上。</li>
 * </ul>
 * 两条断言都依赖运行期传入的 URL，因此不是对着字面量自证。
 */
final class ItSchemaGuard {

    private ItSchemaGuard() {
    }

    static void assertItSchemaIsolation(String schema, String jdbcBaseUrl) {
        assertTrue(schema.endsWith("_it") && !schema.endsWith("_fwit"),
                "IT 只能用 *_it（且不得用 *_fwit）后缀的库，实际：" + schema);
        assertFalse(schema.equals(businessDbOf(jdbcBaseUrl)),
                "IT 库名不得与 URL 指向的业务库同名，实际：" + schema);
    }

    /** 从 JDBC URL 取库名：按 "?" 切掉参数，再取最后一个 "/" 之后的部分。 */
    static String businessDbOf(String jdbcUrl) {
        int query = jdbcUrl.indexOf('?');
        String head = query < 0 ? jdbcUrl : jdbcUrl.substring(0, query);
        return head.substring(head.lastIndexOf('/') + 1);
    }
}
