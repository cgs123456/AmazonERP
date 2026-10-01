package com.amz.mapper;

import com.amz.model.PaymentCollection;
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
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 台账批量幂等写：真的用 MyBatis 执行一遍 mapper 方法。
 * <p>
 * 为什么需要它：语义此前是在隔离 MySQL 上跑"从源码模板手工代入生成的 SQL"核对的，
 * 那只证明了 SQL 文本对，没证明 <b>MyBatis 把 {@code <foreach>} 展开成什么</b> ——
 * 占位符名写错、别名与实体属性对不上，都是这一层才会暴露的问题（而且不会报错，
 * 只会静默写错列）。这里直接调用 {@link PaymentCollectionMapper#upsertBatch}，
 * 断言的是数据库里最终的样子。
 * <p>
 * 安全性：只在 {@code *_it} 结尾且明确避开 {@code *_fwit} 的专用库上建表/清表
 * （库名护栏见 {@link ItSchemaGuard}），不设环境变量时整类跳过，
 * 不影响无 MySQL 的开发机与 CI。
 *
 * <p>触发：{@code COLLECTION_UPSERT_IT_URL / _USER / _PASSWORD}。
 */
@EnabledIfEnvironmentVariable(named = "COLLECTION_UPSERT_IT_URL", matches = ".+")
@DisplayName("台账 upsert：经 MyBatis 真实执行的幂等结果")
class PaymentCollectionUpsertMySqlIT {

    private static final String SCHEMA = "amz_finance_collection_it";
    private static final String BASE_URL = System.getenv("COLLECTION_UPSERT_IT_URL");
    private static final String USER = System.getenv().getOrDefault("COLLECTION_UPSERT_IT_USER", "root");
    private static final String PASSWORD = System.getenv().getOrDefault("COLLECTION_UPSERT_IT_PASSWORD", "");

    private static String jdbcUrl;
    private SqlSession session;
    private PaymentCollectionMapper mapper;

    @BeforeAll
    static void createSchema() throws SQLException {
        ItSchemaGuard.assertItSchemaIsolation(SCHEMA, BASE_URL);
        // 不用正则拆 URL：直接按 "?" 切，保留驱动参数，只把库名换成 IT 专用库
        int q = BASE_URL.indexOf('?');
        String head = q < 0 ? BASE_URL : BASE_URL.substring(0, q);
        String query = q < 0 ? "" : BASE_URL.substring(q);
        assertTrue(head.startsWith("jdbc:mysql://"), "URL 形态不对，无法安全拼出 IT 专用库：" + head);
        int lastSlash = head.lastIndexOf('/');
        String serverUrl = (lastSlash > "jdbc:".length() ? head.substring(0, lastSlash) : head) + query;
        jdbcUrl = serverUrl.substring(0, serverUrl.length() - query.length())
                + "/" + SCHEMA + query;
        try (Connection conn = DriverManager.getConnection(serverUrl, USER, PASSWORD);
             Statement st = conn.createStatement()) {
            st.execute("CREATE DATABASE IF NOT EXISTS " + SCHEMA
                    + " DEFAULT CHARSET utf8mb4 COLLATE utf8mb4_general_ci");
        }
    }

    @BeforeEach
    void setUp() throws SQLException {
        exec("DROP TABLE IF EXISTS amz_payment_collection");
        // 表结构 = V3__payment_collection.sql 经 V7 列/索引改名之后的样子
        exec("CREATE TABLE amz_payment_collection ("
                + "id BIGINT AUTO_INCREMENT PRIMARY KEY, shop_id BIGINT NOT NULL,"
                + "amazon_order_id VARCHAR(64) NOT NULL, currency VARCHAR(8),"
                + "receivable DECIMAL(14,2) DEFAULT 0.00, fee_deducted DECIMAL(14,2) DEFAULT 0.00,"
                + "refunded DECIMAL(14,2) DEFAULT 0.00, reimbursed DECIMAL(14,2) DEFAULT 0.00,"
                + "net_received DECIMAL(14,2) DEFAULT 0.00, shortfall DECIMAL(14,2) DEFAULT NULL,"
                + "deposit_date VARCHAR(40), status VARCHAR(16) DEFAULT 'IN_TRANSIT',"
                + "last_calculated_at DATETIME DEFAULT NULL,"
                + "create_time DATETIME DEFAULT CURRENT_TIMESTAMP,"
                + "update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,"
                + "UNIQUE KEY uk_shop_amazon_order (shop_id, amazon_order_id),"
                + "INDEX idx_shop_status (shop_id, status)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        exec("INSERT INTO amz_payment_collection (shop_id, amazon_order_id, currency, receivable,"
                + " fee_deducted, refunded, reimbursed, net_received, shortfall, deposit_date, status,"
                + " last_calculated_at) VALUES "
                + "(1,'ORD-4','USD',15.00,2.25,0.00,0.00,12.75,1.10,'2000-01-01T00:00:00Z','SHORTFALL','2020-01-01 00:00:00'),"
                + "(1,'ORD-5','USD',20.00,3.00,0.00,0.00,17.00,0.00,'2000-01-01T00:00:00Z','SETTLED','2020-01-01 00:00:00')");

        UnpooledDataSource ds = new UnpooledDataSource("com.mysql.cj.jdbc.Driver", jdbcUrl, USER, PASSWORD);
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setEnvironment(new Environment("it", new JdbcTransactionFactory(), ds));
        configuration.addMapper(PaymentCollectionMapper.class);
        SqlSessionFactory factory = new MybatisSqlSessionFactoryBuilder().build(configuration);
        session = factory.openSession(true);
        mapper = session.getMapper(PaymentCollectionMapper.class);
    }

    @AfterEach
    void tearDown() {
        if (session != null) {
            session.close();
        }
    }

    @Test
    void upsertKeepsShortfallAndRecomputesTheRest() {
        List<PaymentCollection> rows = new ArrayList<>();
        rows.add(row("ORD-4", "16.00", "2.50", "0.00", "13.50", PaymentCollection.STATUS_SETTLED));
        rows.add(row("ORD-5", "21.00", "3.50", "0.00", "17.50", PaymentCollection.STATUS_IN_TRANSIT));
        rows.add(row("ORD-8", "50.00", "8.00", "0.00", "42.00", PaymentCollection.STATUS_SETTLED));

        int affected = mapper.upsertBatch(rows);
        assertTrue(affected > 0, "至少要有写入：affected=" + affected);

        Map<String, Map<String, Object>> byOrder = fetch();
        assertEquals(3, byOrder.size(), "三单都该在台账里：" + byOrder.keySet());

        // 有正短款：金额更新，短款与 SHORTFALL 状态都不许被动
        Map<String, Object> ord4 = byOrder.get("ORD-4");
        assertEquals(0, new BigDecimal("16.00").compareTo((BigDecimal) ord4.get("receivable")));
        assertEquals(0, new BigDecimal("1.10").compareTo((BigDecimal) ord4.get("shortfall")),
                "短款被重算清掉了——索赔依据会凭空消失");
        assertEquals(PaymentCollection.STATUS_SHORTFALL, ord4.get("status"));

        // 短款为 0：保留 0，但状态跟着新的基础值走
        Map<String, Object> ord5 = byOrder.get("ORD-5");
        assertEquals(0, BigDecimal.ZERO.compareTo((BigDecimal) ord5.get("shortfall")));
        assertEquals(PaymentCollection.STATUS_IN_TRANSIT, ord5.get("status"));

        // 全新订单：短款保持 NULL（"没做过费用比对"不能被写成"没有短款"）
        Map<String, Object> ord8 = byOrder.get("ORD-8");
        assertNull(ord8.get("shortfall"));
        assertEquals(PaymentCollection.STATUS_SETTLED, ord8.get("status"));
        assertNotNull(ord8.get("createTime"), "create_time 应由库默认值补齐");
    }

    @Test
    void runningTheSameUpsertTwiceIsIdempotent() {
        List<PaymentCollection> rows = List.of(
                row("ORD-9", "30.00", "4.00", "0.00", "26.00", PaymentCollection.STATUS_SETTLED));
        mapper.upsertBatch(rows);
        mapper.upsertBatch(rows);

        Map<String, Object> ord9 = fetch().get("ORD-9");
        assertEquals(0, new BigDecimal("30.00").compareTo((BigDecimal) ord9.get("receivable")));
        assertEquals(1, count("SELECT COUNT(1) FROM amz_payment_collection WHERE amazon_order_id='ORD-9'"),
                "第二次跑不该多出一行");
    }

    @Test
    void shortStatusRuleFollowsExistingShortfallEvenWhenNewStatusDiffers() {
        mapper.upsertBatch(List.of(row("ORD-4", "99.00", "9.00", "99.00", "0.00",
                PaymentCollection.STATUS_REFUNDED)));

        Map<String, Object> ord4 = fetch().get("ORD-4");
        assertEquals(0, new BigDecimal("99.00").compareTo((BigDecimal) ord4.get("receivable")),
                "金额照常更新");
        assertEquals(PaymentCollection.STATUS_SHORTFALL, ord4.get("status"),
                "有未处理短款的订单不能被「已回/已退」掩盖——那是需要索赔动作的钱");
    }

    private static PaymentCollection row(String order, String receivable, String fee, String refunded,
                                         String net, String status) {
        PaymentCollection pc = new PaymentCollection();
        pc.setShopId(1L);
        pc.setAmazonOrderId(order);
        pc.setCurrency("USD");
        pc.setReceivable(new BigDecimal(receivable));
        pc.setFeeDeducted(new BigDecimal(fee));
        pc.setRefunded(new BigDecimal(refunded));
        pc.setReimbursed(BigDecimal.ZERO);
        pc.setNetReceived(new BigDecimal(net));
        pc.setDepositDate("2000-01-01T00:00:00Z");
        pc.setStatus(status);
        pc.setLastCalculatedAt(java.time.LocalDateTime.of(2026, 10, 1, 0, 0));
        return pc;
    }

    private Map<String, Map<String, Object>> fetch() {
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        try (Connection conn = DriverManager.getConnection(jdbcUrl, USER, PASSWORD);
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT * FROM amz_payment_collection WHERE shop_id = 1")) {
            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("receivable", rs.getBigDecimal("receivable"));
                row.put("shortfall", rs.getBigDecimal("shortfall"));
                row.put("status", rs.getString("status"));
                row.put("createTime", rs.getObject("create_time"));
                out.put(rs.getString("amazon_order_id"), row);
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return out;
    }

    private static int count(String sql) {
        try (Connection conn = DriverManager.getConnection(jdbcUrl, USER, PASSWORD);
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getInt(1);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void exec(String sql) {
        try (Connection conn = DriverManager.getConnection(jdbcUrl, USER, PASSWORD);
             Statement st = conn.createStatement()) {
            st.execute(sql);
        } catch (SQLException e) {
            throw new IllegalStateException("IT 建表失败：" + sql, e);
        }
    }

}
