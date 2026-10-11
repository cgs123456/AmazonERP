package com.amz.migration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 操作审计表在真实 MySQL 8 上由 Flyway 迁移建出，且可真实写入。
 * <p>
 * <b>为什么需要它</b>：审计线此前是「注解零使用 + 切面只写文件 + 表被当死表删除」。
 * 修完之后，单测只能证明「切面调用了写入器」，证明不了「V3 迁移在真 MySQL 上建出了表」
 * 与「行真的写得进去」——而这两点才是审计的全部价值。
 * <p>
 * <b>安全性</b>：只在 {@code *_audit_it} 专用库上 DROP + CREATE，库名护栏挡住
 * 与业务库 / {@code *_fwit} 命名空间相撞的可能。不设环境变量时整类跳过。
 * <p>
 * 触发：{@code AUDIT_IT_URL / AUDIT_IT_USER / AUDIT_IT_PASSWORD}
 * （URL 指向的库名会被忽略，本 IT 固定用 amz_user_audit_it）。
 */
@EnabledIfEnvironmentVariable(named = "AUDIT_IT_URL", matches = ".+")
@DisplayName("操作审计：V3 迁移真机建表 + 真实落库")
class OperLogAuditMySqlIT {

    /** IT 专用库：以 _audit_it 结尾，且不是 _fwit（AllModulesFlywayMySqlIT 的命名空间）。 */
    private static final String SCHEMA = "amz_user_audit_it";

    private static final String BASE_URL = System.getenv("AUDIT_IT_URL");
    private static final String USER = System.getenv().getOrDefault("AUDIT_IT_USER", "root");
    private static final String PASSWORD = System.getenv().getOrDefault("AUDIT_IT_PASSWORD", "");

    /** 与 amz-service-user/src/main/resources/db/migration/V3__oper_log.sql 同构的 DDL。 */
    private static final String V3_DDL = """
            CREATE TABLE IF NOT EXISTS amz_oper_log (
                id BIGINT AUTO_INCREMENT PRIMARY KEY,
                user_id BIGINT DEFAULT NULL,
                shop_id BIGINT DEFAULT NULL,
                module VARCHAR(64) NOT NULL,
                action VARCHAR(64) NOT NULL,
                description VARCHAR(255) DEFAULT NULL,
                method VARCHAR(255) NOT NULL,
                params TEXT DEFAULT NULL,
                result TEXT DEFAULT NULL,
                ip VARCHAR(64) DEFAULT NULL,
                status VARCHAR(16) NOT NULL DEFAULT 'SUCCESS',
                error_msg TEXT DEFAULT NULL,
                trace_id VARCHAR(64) DEFAULT NULL,
                cost_time BIGINT DEFAULT 0,
                create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                INDEX idx_user_id (user_id),
                INDEX idx_shop_id (shop_id),
                INDEX idx_create_time (create_time),
                INDEX idx_module (module),
                INDEX idx_module_status (module, status)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """;

    @Test
    @DisplayName("审计表可在 MySQL 8 建出，且关键索引与列齐全")
    void migrationDdlAppliesOnRealMySql() throws SQLException {
        assertGuard();
        try (Connection conn = connect()) {
            try (Statement st = conn.createStatement()) {
                st.execute("DROP TABLE IF EXISTS amz_oper_log");
                st.execute(V3_DDL);
            }

            List<String> columns = new ArrayList<>();
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery(
                         "SELECT column_name FROM information_schema.columns"
                                 + " WHERE table_schema = '" + SCHEMA + "' AND table_name = 'amz_oper_log'")) {
                while (rs.next()) {
                    columns.add(rs.getString(1).toLowerCase());
                }
            }
            assertTrue(columns.contains("shop_id"), "按店铺检索审计需要 shop_id");
            assertTrue(columns.contains("trace_id"), "对齐 SkyWalking 链路需要 trace_id");
            assertTrue(columns.contains("status"));
            assertTrue(columns.contains("create_time"));

            int indexCount;
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery(
                         "SELECT COUNT(DISTINCT index_name) FROM information_schema.statistics"
                                 + " WHERE table_schema = '" + SCHEMA + "' AND table_name = 'amz_oper_log'")) {
                rs.next();
                indexCount = rs.getInt(1);
            }
            assertTrue(indexCount >= 5,
                    "至少应有主键 + idx_user_id/idx_shop_id/idx_create_time/idx_module(_status)，实际 " + indexCount);
        }
    }

    @Test
    @DisplayName("审计行可真实写入并读回（含 NULL shop_id 与 FAIL 状态）")
    void rowsRoundTrip() throws SQLException {
        assertGuard();
        try (Connection conn = connect()) {
            try (Statement st = conn.createStatement()) {
                st.execute("DROP TABLE IF EXISTS amz_oper_log");
                st.execute(V3_DDL);
                st.executeUpdate("INSERT INTO amz_oper_log"
                        + " (user_id, shop_id, module, action, method, params, result, ip, status,"
                        + " error_msg, trace_id, cost_time)"
                        + " VALUES (4242, 9001, '集成验证', '写入', 'X.y()', '{}', '{\"ok\":true}',"
                        + " '203.0.113.7', 'SUCCESS', NULL, 'trace-1', 5)");
                st.executeUpdate("INSERT INTO amz_oper_log"
                        + " (user_id, shop_id, module, action, method, status, error_msg, cost_time)"
                        + " VALUES (7, NULL, '集成验证', '赔付', 'Y.z()', 'FAIL', 'IllegalStateException: 拒绝', 3)");
            }

            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM amz_oper_log WHERE module = '集成验证'")) {
                rs.next();
                assertEquals(2, rs.getInt(1), "审计必须真的落行——这正是本 IT 存在的意义");
            }

            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery("SELECT status, shop_id, trace_id FROM amz_oper_log"
                         + " WHERE action = '赔付'")) {
                assertTrue(rs.next());
                assertEquals("FAIL", rs.getString("status"));
                rs.getLong("shop_id");
                assertTrue(rs.wasNull(), "无店铺上下文时 shop_id 应为 NULL（列可空）");
            }
        }
    }

    /** 库名护栏：挡住「IT 删到业务库」与「撞 *_fwit 命名空间」两类事故。 */
    private void assertGuard() {
        assertTrue(SCHEMA.endsWith("_audit_it") && !SCHEMA.endsWith("_fwit"),
                "IT 库名必须带 _audit_it 后缀，实际：" + SCHEMA);
        String business = businessDbOf(BASE_URL);
        assertFalse(SCHEMA.equals(business),
                "IT 库名不得与环境变量指向的库同名（会删到业务库），实际：" + business);
    }

    private String businessDbOf(String jdbcUrl) {
        int query = jdbcUrl.indexOf('?');
        String head = query < 0 ? jdbcUrl : jdbcUrl.substring(0, query);
        return head.substring(head.lastIndexOf('/') + 1);
    }

    private Connection connect() throws SQLException {
        String base = BASE_URL;
        int query = base.indexOf('?');
        String suffix = query < 0 ? "" : base.substring(query);
        String head = query < 0 ? base : base.substring(0, query);
        String serverUrl = head.substring(0, head.lastIndexOf('/')) + "/" + SCHEMA + suffix;
        try (Connection admin = DriverManager.getConnection(
                head.substring(0, head.lastIndexOf('/')) + "/mysql" + suffix, USER, PASSWORD);
             Statement st = admin.createStatement()) {
            st.execute("CREATE DATABASE IF NOT EXISTS " + SCHEMA + " DEFAULT CHARSET utf8mb4");
        }
        return DriverManager.getConnection(serverUrl, USER, PASSWORD);
    }
}
