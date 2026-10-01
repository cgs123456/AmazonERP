package com.amz.mapper;

import com.amz.model.ProfitDetail;
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
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 报表订单数口径：真的用 MyBatis 打一次数据库。
 * <p>
 * 静态契约能钉住 SQL 文本，钉不住它能跑、也钉不住"去重到底去了什么"。这里验的是三件事：
 * <ol>
 *   <li>{@code COUNT(DISTINCT amazon_order_id)} 与 {@code COUNT(1)} 在同一份数据上确实不同
 *       （实测 fixture：7 行 / 4 个订单；旧口径把 7 当订单数）；</li>
 *   <li>各 ASIN 的去重数<b>相加</b>（3 + 2 = 5）大于全店去重数（4）——
 *       这就是 {@code totalOrders} 必须另查一条的原因；</li>
 *   <li>新增的 {@code countDistinctOrders} 经 MyBatis 执行时，{@code <if>} 的日期边界
 *       真的参与过滤（区间外查询返回 0 而不是全店数）。</li>
 * </ol>
 * 只在 {@code *_it}（且避开 {@code *_fwit}）的专用库上建表；不设环境变量整类跳过。
 * 库名护栏规则与 finance 模块的 {@code ItSchemaGuard} 一致，跨模块共享要引 test-jar，
 * 这里宁可有 6 行重复。
 * <p>
 * 触发：{@code PROFIT_ORDER_COUNT_IT_URL / _USER / _PASSWORD}。
 */
@EnabledIfEnvironmentVariable(named = "PROFIT_ORDER_COUNT_IT_URL", matches = ".+")
@DisplayName("报表订单数：真库上的去重、跨 ASIN 重复计与日期边界")
class ProfitOrderCountMySqlIT {

    private static final String SCHEMA = "amz_report_profit_it";
    private static final String BASE_URL = System.getenv("PROFIT_ORDER_COUNT_IT_URL");
    private static final String USER = System.getenv().getOrDefault("PROFIT_ORDER_COUNT_IT_USER", "root");
    private static final String PASSWORD = System.getenv().getOrDefault("PROFIT_ORDER_COUNT_IT_PASSWORD", "");

    private static String jdbcUrl;
    private static SqlSessionFactory factory;
    private SqlSession session;
    private ProfitDetailMapper mapper;

    @BeforeAll
    static void bootstrap() throws SQLException {
        assertTrue(SCHEMA.endsWith("_it") && !SCHEMA.endsWith("_fwit"),
                "IT 只能用 *_it（且不得用 *_fwit）后缀的库，实际：" + SCHEMA);
        int q = BASE_URL.indexOf('?');
        String head = q < 0 ? BASE_URL : BASE_URL.substring(0, q);
        String query = q < 0 ? "" : BASE_URL.substring(q);
        assertTrue(head.startsWith("jdbc:mysql://"), "URL 形态不对：" + head);
        int lastSlash = head.lastIndexOf('/');
        String serverUrl = (lastSlash > "jdbc:".length() ? head.substring(0, lastSlash) : head) + query;
        assertFalse(SCHEMA.equals(head.substring(head.lastIndexOf('/') + 1)),
                "IT 库名不得与 URL 指向的业务库同名：" + SCHEMA);
        jdbcUrl = serverUrl.substring(0, serverUrl.length() - query.length()) + "/" + SCHEMA + query;
        try (Connection conn = DriverManager.getConnection(serverUrl, USER, PASSWORD);
             Statement st = conn.createStatement()) {
            st.execute("CREATE DATABASE IF NOT EXISTS " + SCHEMA + " DEFAULT CHARSET utf8mb4");
        }
        UnpooledDataSource ds = new UnpooledDataSource("com.mysql.cj.jdbc.Driver", jdbcUrl, USER, PASSWORD);
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setEnvironment(new Environment("it", new JdbcTransactionFactory(), ds));
        configuration.addMapper(ProfitDetailMapper.class);
        factory = new MybatisSqlSessionFactoryBuilder().build(configuration);
    }

    @BeforeEach
    void createTableAndLoadFixture() throws SQLException {
        try (Connection conn = DriverManager.getConnection(jdbcUrl, USER, PASSWORD);
             Statement st = conn.createStatement()) {
            st.execute("DROP TABLE IF EXISTS amz_profit_detail");
            // 列名与 V1__init.sql 对齐；口径只涉及这几列
            st.execute("CREATE TABLE amz_profit_detail ("
                    + "id BIGINT AUTO_INCREMENT PRIMARY KEY, shop_id BIGINT NOT NULL,"
                    + "amazon_order_id VARCHAR(32) NOT NULL, asin VARCHAR(20) NOT NULL,"
                    + "sku VARCHAR(64), report_date DATE NOT NULL,"
                    + "product_sales DECIMAL(10,2) DEFAULT 0, product_cost DECIMAL(10,2) DEFAULT 0,"
                    + "advertising_cost DECIMAL(10,2) DEFAULT 0, fba_fees DECIMAL(10,2) DEFAULT 0,"
                    + "referral_fee DECIMAL(10,2) DEFAULT 0, variable_closing_fee DECIMAL(10,2) DEFAULT 0,"
                    + "storage_fee DECIMAL(10,2) DEFAULT 0, gross_profit DECIMAL(10,2) DEFAULT 0,"
                    + "net_profit DECIMAL(10,2) DEFAULT 0,"
                    + "INDEX idx_shop_date (shop_id, report_date)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        }
        session = factory.openSession(true);
        mapper = session.getMapper(ProfitDetailMapper.class);
        // 7 行 / 4 个订单：111-1 在 B1 有两行，111-3 跨 B1+B2，另有 9 月外的一行
        insert(1L, "111-1", "B1", "2026-09-01", "10.00", "1.00");
        insert(1L, "111-1", "B1", "2026-09-02", "10.00", "1.00");
        insert(1L, "111-2", "B1", "2026-09-01", "15.00", "2.00");
        insert(1L, "111-3", "B2", "2026-09-01", "20.00", "3.00");
        insert(1L, "111-3", "B2", "2026-09-03", "20.00", "3.00");
        insert(1L, "111-3", "B1", "2026-09-03", "5.00", "0.50");
        insert(1L, "111-4", "B2", "2026-09-05", "7.00", "1.00");
        insert(1L, "111-5", "B1", "2026-10-01", "9.00", "0.90");   // 区间外
        insert(2L, "222-1", "B1", "2026-09-02", "99.00", "9.00");  // 跨店
    }

    @AfterEach
    void closeSession() {
        if (session != null) {
            session.close();
        }
    }

    @Test
    void distinctOrderCountDiffersFromRowCountOnRealData() {
        List<Map<String, Object>> groups = mapper.sumByAsin(1L,
                LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30));

        assertEquals(2, groups.size(), "9 月区间里只有 B1/B2 两个 ASIN（10 月那行不该进来）");
        Map<String, Object> b1 = byAsin(groups, "B1");
        Map<String, Object> b2 = byAsin(groups, "B2");
        assertEquals(3L, ((Number) b1.get("orderCount")).longValue(),
                "B1 在 9 月是 111-1/111-2/111-3 三单（111-1 有两行也只算一单）");
        assertEquals(2L, ((Number) b2.get("orderCount")).longValue(),
                "B2 在 9 月是 111-3/111-4 两单");

        // 旧口径就是数这些行
        assertEquals(7, rowCountRaw(1L, "2026-09-01", "2026-09-30"),
                "9 月区间内 7 行明细（10 月行与跨店行除外），而去重只有 4 单 —— 旧口径把 7 当订单数");
    }

    @Test
    void sumOfAsinCountsOverstatesTheShopTotal() {
        LocalDate from = LocalDate.of(2026, 9, 1);
        LocalDate to = LocalDate.of(2026, 9, 30);
        List<Map<String, Object>> groups = mapper.sumByAsin(1L, from, to);
        long sumOfParts = groups.stream()
                .mapToLong(g -> ((Number) g.get("orderCount")).longValue())
                .sum();
        Long shopWide = mapper.countDistinctOrders(1L, from, to);

        assertEquals(5L, sumOfParts, "B1 三单 + B2 两单");
        assertEquals(4L, shopWide.longValue(),
                "全店去重是 4 —— 111-3 同时买了 B1 和 B2，按 ASIN 相加会把它数两次");
        assertTrue(sumOfParts > shopWide,
                "这条用例的前提就是相加 > 真实；相等说明 fixture 没覆盖跨 ASIN 订单");
    }

    @Test
    void dateWindowIsActuallyAppliedByTheCountQuery() {
        Long august = mapper.countDistinctOrders(1L,
                LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31));
        assertEquals(0L, august.longValue(), "8 月没有数据；不是 0 说明 <if> 边界没参与过滤");

        Long allTime = mapper.countDistinctOrders(1L, null, null);
        assertEquals(5L, allTime.longValue(), "不限日期时是 5 个订单（含 10 月那单，不含跨店单）");
    }

    private void insert(Long shopId, String orderId, String asin, String date,
                        String sales, String netProfit) throws SQLException {
        ProfitDetail row = new ProfitDetail();
        row.setShopId(shopId);
        row.setAmazonOrderId(orderId);
        row.setAsin(asin);
        row.setReportDate(LocalDate.parse(date));
        row.setProductSales(new BigDecimal(sales));
        row.setNetProfit(new BigDecimal(netProfit));
        mapper.insert(row);
    }

    private int rowCountRaw(Long shopId, String from, String to) {
        String sql = "SELECT COUNT(1) FROM amz_profit_detail WHERE shop_id = ?"
                + " AND report_date BETWEEN ? AND ?";
        try (Connection conn = DriverManager.getConnection(jdbcUrl, USER, PASSWORD);
             PreparedStatement st = conn.prepareStatement(sql)) {
            st.setLong(1, shopId);
            st.setString(2, from);
            st.setString(3, to);
            try (ResultSet rs = st.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Map<String, Object> byAsin(List<Map<String, Object>> groups, String asin) {
        return groups.stream().filter(g -> asin.equals(g.get("asin"))).findFirst()
                .orElseThrow(() -> new AssertionError("聚合结果里没有 " + asin + "：" + groups));
    }
}
