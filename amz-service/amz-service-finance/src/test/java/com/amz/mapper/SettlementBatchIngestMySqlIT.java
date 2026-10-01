package com.amz.mapper;

import com.amz.batch.BatchInserts;
import com.amz.model.SettlementDetail;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.extension.toolkit.Db;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
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
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 结算明细批量写：真的用 MyBatis / MyBatis-Plus 跑一遍。
 * <p>
 * 这里守的是三个"只在真机上才会露馅"的前提：
 * <ol>
 *   <li>{@code Db.saveBatch} 在没有 Spring 容器时能否工作、500 行是否真的一条批次落库；</li>
 *   <li>重复 row_key 是否会让整批抛错（决定"退回逐条"这条路会不会被走到）；</li>
 *   <li>抛出来的异常形态能不能被生产代码用的那一份
 *       {@link BatchInserts#isDuplicateKey(Throwable)} 认出来 —— 认不出来就会把
 *       "已经在库里"的行记成失败，报表随之少一笔。</li>
 * </ol>
 * 只在 {@code *_it} 结尾、且明确不是 {@code *_fwit} 的库上建表：后一个是
 * {@code AllModulesFlywayMySqlIT} 对每个业务库 DROP + CREATE 的命名空间，两边共用
 * 就会互相删表（本 IT 第一版就踩了这一点）。不设环境变量整类跳过。触发方式同
 * {@code PaymentCollectionUpsertMySqlIT}（换成 SETTLEMENT_BATCH_IT_* 三个变量）。
 */
@EnabledIfEnvironmentVariable(named = "SETTLEMENT_BATCH_IT_URL", matches = ".+")
@DisplayName("结算明细批量写：Db.saveBatch 与重复键的真实形态")
class SettlementBatchIngestMySqlIT {

    private static final String SCHEMA = "amz_finance_batch_it";
    private static final String BASE_URL = System.getenv("SETTLEMENT_BATCH_IT_URL");
    private static final String USER = System.getenv().getOrDefault("SETTLEMENT_BATCH_IT_USER", "root");
    private static final String PASSWORD = System.getenv().getOrDefault("SETTLEMENT_BATCH_IT_PASSWORD", "");

    private static String jdbcUrl;
    private static SqlSessionFactory factory;

    @BeforeAll
    static void bootstrap() throws SQLException {
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
            st.execute("CREATE DATABASE IF NOT EXISTS " + SCHEMA + " DEFAULT CHARSET utf8mb4");
        }
        UnpooledDataSource ds = new UnpooledDataSource("com.mysql.cj.jdbc.Driver", jdbcUrl, USER, PASSWORD);
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setEnvironment(new Environment("it", new JdbcTransactionFactory(), ds));
        configuration.addMapper(SettlementDetailMapper.class);
        factory = new MybatisSqlSessionFactoryBuilder().build(configuration);
        // Db 需要一个已注册的 TableInfo/Configuration 才能工作，先打开一次会话把 mapper 装起来
        try (SqlSession ignored = factory.openSession(true)) {
            ignored.getMapper(SettlementDetailMapper.class);
        }
    }

    @BeforeEach
    void createTable() throws SQLException {
        try (Connection conn = DriverManager.getConnection(jdbcUrl, USER, PASSWORD);
             Statement st = conn.createStatement()) {
            st.execute("DROP TABLE IF EXISTS amz_settlement_detail");
            st.execute("CREATE TABLE amz_settlement_detail ("
                    + "id BIGINT AUTO_INCREMENT PRIMARY KEY, shop_id BIGINT NOT NULL,"
                    + "settlement_id VARCHAR(32), amazon_order_id VARCHAR(64) NULL,"
                    + "sku VARCHAR(64), transaction_type VARCHAR(32), amount_type VARCHAR(64),"
                    + "amount DECIMAL(14,2), currency VARCHAR(8), deposit_date VARCHAR(40),"
                    + "row_key VARCHAR(128) NOT NULL, source VARCHAR(32),"
                    + "create_time DATETIME DEFAULT CURRENT_TIMESTAMP,"
                    + "UNIQUE KEY uk_row_key (row_key),"
                    + "INDEX idx_shop_amazon_order (shop_id, amazon_order_id)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        }
    }

    @Test
    void saveBatchWritesEveryRow() {
        List<SettlementDetail> rows = details(500);
        boolean ok = Db.saveBatch(rows);
        assertTrue(ok, "Db.saveBatch 应返回 true");
        assertEquals(500, count("SELECT COUNT(1) FROM amz_settlement_detail"));
    }

    @Test
    void duplicateRowKeyMakesTheWholeBatchThrow() {
        Db.saveBatch(details(3));
        // 同一批行再来一次：唯一键冲突必须让批量抛出，"退回逐条"才有意义
        RuntimeException ex = assertThrows(RuntimeException.class, () -> Db.saveBatch(details(3)));
        assertTrue(BatchInserts.isDuplicateKey(ex),
                "异常形态不在生产判定的识别范围内，逐条兜底会把「已在库里」误记为失败："
                        + chain(ex));
    }

    @Test
    void perRowRetrySeesDuplicateAsSkipped() {
        Db.saveBatch(details(2));
        List<SettlementDetail> again = details(2);
        // 逐条兜底：Mapper 原生 insert 撞唯一键时同样要能被认出来
        try (SqlSession session = factory.openSession(true)) {
            SettlementDetailMapper mapper = session.getMapper(SettlementDetailMapper.class);
            RuntimeException ex = assertThrows(RuntimeException.class, () -> mapper.insert(again.get(0)));
            assertTrue(BatchInserts.isDuplicateKey(ex), "逐条路径的异常形态无法识别：" + chain(ex));
            assertTrue(ex.getCause() instanceof SQLIntegrityConstraintViolationException
                            || containsSqlIntegrityCause(ex),
                    "驱动应给出 SQLIntegrityConstraintViolationException，实测因果链：" + chain(ex));
        }
    }

    private static boolean containsSqlIntegrityCause(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof SQLIntegrityConstraintViolationException) {
                return true;
            }
        }
        return false;
    }

    private static String chain(Throwable error) {
        StringBuilder sb = new StringBuilder();
        for (Throwable t = error; t != null; t = t.getCause()) {
            sb.append(t.getClass().getName()).append(": ")
                    .append(String.valueOf(t.getMessage()).replace('\n', ' ')).append(" <- ");
        }
        return sb.toString();
    }

    private static List<SettlementDetail> details(int count) {
        List<SettlementDetail> out = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            SettlementDetail d = new SettlementDetail();
            d.setShopId(1L);
            d.setSettlementId("900001");
            d.setAmazonOrderId("111-" + i);
            d.setSku("SKU-" + i);
            d.setTransactionType("Order");
            d.setAmountType("Principal");
            d.setAmount(new BigDecimal("10.00"));
            d.setCurrency("USD");
            d.setDepositDate("2026-09-08T00:00:00Z");
            d.setRowKey("rk-" + i);
            d.setSource(SettlementDetail.SOURCE_REPORT);
            d.setCreateTime(LocalDateTime.now());
            out.add(d);
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
}
