package com.amz.mapper;

import com.amz.model.ListingHealth;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Listing 健康度汇总：真的用 MyBatis 打一次数据库。
 * <p>
 * 这里守的是 mock 永远测不出来的一件事 —— <b>大小写敏感</b>。本表 DDL 是
 * {@code COLLATE=utf8mb4_unicode_ci}，MySQL 会把 'ok'/'Ok' 也算进 {@code = 'OK'}，
 * 而原实现 {@code "OK".equals(severity)} 不会。聚合下沉时若不显式 COLLATE，
 * {@code ok}/{@code healthRate} 会在所有单测全绿的情况下变大。
 * <p>
 * 只在 {@code *_it}（且明确避开 {@code *_fwit}）的专用库上建表；不设环境变量整类跳过。
 * 库名护栏与 finance 模块的 {@code ItSchemaGuard} 同规则 —— 那份在另一个模块的测试源里，
 * 跨模块共享要引入 test-jar 依赖，这里宁可有 6 行重复也不动构建结构。
 * <p>
 * 触发：{@code LISTING_HEALTH_IT_URL / _USER / _PASSWORD}。
 */
@EnabledIfEnvironmentVariable(named = "LISTING_HEALTH_IT_URL", matches = ".+")
@DisplayName("Listing 健康度聚合：真库上的大小写敏感与整店口径")
class ListingHealthSummaryMySqlIT {

    private static final String SCHEMA = "amz_product_health_it";
    private static final String BASE_URL = System.getenv("LISTING_HEALTH_IT_URL");
    private static final String USER = System.getenv().getOrDefault("LISTING_HEALTH_IT_USER", "root");
    private static final String PASSWORD = System.getenv().getOrDefault("LISTING_HEALTH_IT_PASSWORD", "");

    private static String jdbcUrl;
    private static SqlSessionFactory factory;
    private SqlSession session;
    private ListingHealthMapper mapper;

    @BeforeAll
    static void bootstrap() throws SQLException {
        assertItSchemaIsolation(SCHEMA, BASE_URL);
        int q = BASE_URL.indexOf('?');
        String head = q < 0 ? BASE_URL : BASE_URL.substring(0, q);
        String query = q < 0 ? "" : BASE_URL.substring(q);
        assertTrue(head.startsWith("jdbc:mysql://"), "URL 形态不对：" + head);
        int lastSlash = head.lastIndexOf('/');
        String serverUrl = (lastSlash > "jdbc:".length() ? head.substring(0, lastSlash) : head) + query;
        jdbcUrl = serverUrl.substring(0, serverUrl.length() - query.length()) + "/" + SCHEMA + query;
        try (Connection conn = DriverManager.getConnection(serverUrl, USER, PASSWORD);
             Statement st = conn.createStatement()) {
            st.execute("CREATE DATABASE IF NOT EXISTS " + SCHEMA + " DEFAULT CHARSET utf8mb4");
        }
        UnpooledDataSource ds = new UnpooledDataSource("com.mysql.cj.jdbc.Driver", jdbcUrl, USER, PASSWORD);
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setEnvironment(new Environment("it", new JdbcTransactionFactory(), ds));
        // addMapper 会为 BaseMapper<ListingHealth> 注册实体列缓存，insert/select 才拼得出语句
        configuration.addMapper(ListingHealthMapper.class);
        factory = new MybatisSqlSessionFactoryBuilder().build(configuration);
    }

    @BeforeEach
    void openSessionAndCreateTable() throws SQLException {
        try (Connection conn = DriverManager.getConnection(jdbcUrl, USER, PASSWORD);
             Statement st = conn.createStatement()) {
            st.execute("DROP TABLE IF EXISTS amz_listing_health");
            // 与 V1__init.sql 同表同排序规则（只留聚合涉及的列）
            st.execute("CREATE TABLE amz_listing_health ("
                    + "id BIGINT AUTO_INCREMENT PRIMARY KEY, shop_id BIGINT NOT NULL,"
                    + "asin VARCHAR(16) NOT NULL, sku VARCHAR(64),"
                    + "suppressed_reason VARCHAR(128), health_score INT DEFAULT 100,"
                    + "severity VARCHAR(10) DEFAULT 'OK', check_time DATETIME DEFAULT CURRENT_TIMESTAMP,"
                    + "UNIQUE KEY uk_shop_asin (shop_id, asin))"
                    + " ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci");
        }
        session = factory.openSession(true);
        mapper = session.getMapper(ListingHealthMapper.class);
    }

    @AfterEach
    void closeSession() {
        if (session != null) {
            session.close();
        }
    }

    @Test
    void severityCountsAreCaseSensitiveOnRealData() {
        insert(1L, "A1", 100, "OK");
        insert(1L, "A2", 70, "WARNING");
        insert(1L, "A3", 30, "CRITICAL");
        insert(1L, "A4", 95, "ok");     // 小写：Java 的 equals 不算 OK
        insert(1L, "A5", 98, "Ok");     // 混合大小写：同上
        insert(1L, "A6", 50, null);     // 无严重度
        insert(2L, "B1", 10, "OK");     // 跨店行：不该进来

        Map<String, Object> row = mapper.aggregateHealthSummary(1L);

        long ok = ((Number) row.get("okCount")).longValue();
        long warning = ((Number) row.get("warningCount")).longValue();
        long critical = ((Number) row.get("criticalCount")).longValue();
        assertEquals(1L, ok, "'ok'/'Ok' 被算进 OK 就说明 COLLATE 失效（Java 侧 equals 只认 'OK'）");
        assertEquals(1L, warning, () -> "整店聚合结果：" + row);
        assertEquals(1L, critical, () -> "整店聚合结果：" + row);
        assertEquals(6L, ((Number) row.get("total")).longValue(), "跨店行不得计入");

        // 与"逐行 Java 语义"对照：这是等价性检查，不是对着 SQL 复读
        List<ListingHealth> loaded = listAll(1L);
        assertEquals(countJavaEquals(loaded, "OK"), ok, "SQL 与 Java 逐行判定必须一致");
        assertEquals(countJavaEquals(loaded, "WARNING"), warning);
        assertEquals(countJavaEquals(loaded, "CRITICAL"), critical);
        assertEquals(loaded.size(), ((Number) row.get("total")).longValue());

        long scoreCount = ((Number) row.get("scoreCount")).longValue();
        BigDecimal scoreSum = (BigDecimal) row.get("scoreSum");
        assertEquals(6L, scoreCount, "health_score 全部非空时计数应等于 total");
        assertEquals(0, scoreSum.compareTo(BigDecimal.valueOf(
                loaded.stream().mapToInt(ListingHealth::getHealthScore).sum())));
    }

    @Test
    void nullScoresAreExcludedFromTheAverageDenominator() {
        insert(1L, "A1", 100, "OK");
        insert(1L, "A2", null, null);   // DDL 允许 health_score 为空

        Map<String, Object> row = mapper.aggregateHealthSummary(1L);

        assertEquals(2L, ((Number) row.get("total")).longValue());
        assertEquals(1L, ((Number) row.get("scoreCount")).longValue(),
                "NULL 分数不进分母；原先 Java 侧是直接 NPE，整店接口 500");
        assertEquals(0, ((BigDecimal) row.get("scoreSum")).compareTo(BigDecimal.valueOf(100)));
    }

    @Test
    void worstListingsAreLowestScoresWithAStableTieBreak() {
        insert(1L, "T10", 10, "CRITICAL");
        insert(1L, "T20", 20, "CRITICAL");
        insert(1L, "T20B", 20, "CRITICAL");
        insert(1L, "T30", 30, "WARNING");
        insert(1L, "T40", 40, "WARNING");
        insert(1L, "T50", 50, "OK");
        insert(2L, "OTHER", 1, "CRITICAL"); // 跨店：不该出现

        List<Map<String, Object>> worst = mapper.selectWorstListings(1L, 5);

        assertEquals(5, worst.size(), "只取最差 5 条");
        assertEquals(List.of("T10", "T20", "T20B", "T30", "T40"),
                worst.stream().map(r -> String.valueOf(r.get("asin"))).toList(),
                "分数升序 + 同分按 id 升序（原来无判别式，同分店两次请求可能给出不同清单）");
        assertEquals(40, ((Number) worst.get(4).get("healthScore")).intValue());
        assertEquals("图片缺失", worst.get(0).get("suppressedReason"), "展示要的那一列得带回来");
    }

    /**
     * 夹具走原生 SQL 而不是 {@code mapper.insert(entity)}：MP 默认不写 null 字段，
     * 于是 {@code health_score} / {@code severity} 会被 DDL 的 {@code DEFAULT 100 / 'OK'}
     * 填掉——IT 第一版就是这么把"NULL 行"测成了"OK 行"，两个断言同时红。
     * 空值路径只能显式插 NULL。
     */
    private void insert(Long shopId, String asin, Integer score, String severity) {
        String sql = "INSERT INTO amz_listing_health (shop_id, asin, health_score, severity, suppressed_reason)"
                + " VALUES (?, ?, ?, ?, '图片缺失')";
        try (Connection conn = DriverManager.getConnection(jdbcUrl, USER, PASSWORD);
             PreparedStatement st = conn.prepareStatement(sql)) {
            st.setLong(1, shopId);
            st.setString(2, asin);
            if (score == null) {
                st.setNull(3, Types.INTEGER);
            } else {
                st.setInt(3, score);
            }
            if (severity == null) {
                st.setNull(4, Types.VARCHAR);
            } else {
                st.setString(4, severity);
            }
            st.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("插入夹具失败 asin=" + asin, e);
        }
    }

    /** 用 JDBC 直读整店行，交给"逐行 Java 语义"当对照组。 */
    private List<ListingHealth> listAll(Long shopId) {
        List<ListingHealth> out = new ArrayList<>();
        try (Connection conn = DriverManager.getConnection(jdbcUrl, USER, PASSWORD);
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT asin, health_score, severity FROM amz_listing_health"
                     + " WHERE shop_id = " + shopId + " ORDER BY id")) {
            while (rs.next()) {
                ListingHealth h = new ListingHealth();
                h.setAsin(rs.getString("asin"));
                h.setHealthScore((Integer) rs.getObject("health_score"));
                h.setSeverity(rs.getString("severity"));
                out.add(h);
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return out;
    }

    private static long countJavaEquals(List<ListingHealth> rows, String severity) {
        return rows.stream().filter(h -> severity.equals(h.getSeverity())).count();
    }

    private static void assertItSchemaIsolation(String schema, String baseUrl) {
        assertTrue(schema.endsWith("_it") && !schema.endsWith("_fwit"),
                "IT 只能用 *_it（且不得用 *_fwit）后缀的库，实际：" + schema);
        int query = baseUrl.indexOf('?');
        String head = query < 0 ? baseUrl : baseUrl.substring(0, query);
        assertFalse(schema.equals(head.substring(head.lastIndexOf('/') + 1)),
                "IT 库名不得与 URL 指向的业务库同名，实际：" + schema);
    }
}
