package com.amz.service.impl;

import com.amz.context.UserContext;
import com.amz.exception.CodeErrorException;
import com.amz.mapper.ShopMapper;
import com.amz.mapper.UserMapper;
import com.amz.mapper.UserShopMapper;
import com.amz.model.pojo.Shop;
import com.amz.model.pojo.User;
import com.amz.model.pojo.UserShop;
import com.amz.model.vo.ShopVo;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 店铺主数据服务在真实 MySQL 上的读写与归属隔离。
 * <p>
 * <b>为什么需要它</b>：单测 mock 掉了 Mapper，只能证明「调用序列对」，证明不了
 * 「amz_shop / amz_user_shop 的表结构真的能承载这些读写」——列名拼错、类型不符
 * 都会在这一层才暴露（而且 MyBatis 常常静默）。这条线此前完全没有端点，
 * 前端只能用写死的假店铺，所以更需要一次真机证据。
 * <p>
 * <b>安全性</b>：只在 {@code *_shop_it} 专用库上 DROP + CREATE，库名护栏挡住
 * 「删到业务库」与「撞 *_fwit」。不设环境变量时整类跳过。
 * <p>
 * 触发：{@code SHOP_IT_URL / SHOP_IT_USER / SHOP_IT_PASSWORD}
 */
@EnabledIfEnvironmentVariable(named = "SHOP_IT_URL", matches = ".+")
@DisplayName("店铺主数据：真机读写与归属隔离")
class ShopServiceMySqlIT {

    private static final String SCHEMA = "amz_user_shop_it";
    private static final String BASE_URL = System.getenv("SHOP_IT_URL");
    private static final String USER = System.getenv().getOrDefault("SHOP_IT_USER", "root");
    private static final String PASSWORD = System.getenv().getOrDefault("SHOP_IT_PASSWORD", "");

    private static SqlSessionFactory factory;

    @BeforeAll
    static void prepareSchemaAndFactory() throws SQLException {
        assertGuard();
        String base = BASE_URL;
        int query = base.indexOf('?');
        String suffix = query < 0 ? "" : base.substring(query);
        String head = query < 0 ? base : base.substring(0, query);
        String serverBase = head.substring(0, head.lastIndexOf('/'));

        try (Connection admin = DriverManager.getConnection(serverBase + "/mysql" + suffix, USER, PASSWORD);
             Statement st = admin.createStatement()) {
            st.execute("CREATE DATABASE IF NOT EXISTS " + SCHEMA + " DEFAULT CHARSET utf8mb4");
        }
        try (Connection conn = DriverManager.getConnection(serverBase + "/" + SCHEMA + suffix, USER, PASSWORD);
             Statement st = conn.createStatement()) {
            st.execute("DROP TABLE IF EXISTS amz_shop");
            st.execute("DROP TABLE IF EXISTS amz_user_shop");
            st.execute("DROP TABLE IF EXISTS amz_user");
            st.execute("CREATE TABLE amz_user ("
                    + " id BIGINT AUTO_INCREMENT PRIMARY KEY,"
                    + " phone VARCHAR(20),"
                    + " nickname VARCHAR(50),"
                    + " image VARCHAR(500),"
                    + " sex VARCHAR(10),"
                    + " birthday VARCHAR(20),"
                    + " address VARCHAR(200),"
                    + " role VARCHAR(50) NOT NULL DEFAULT 'VIEWER',"
                    // 必须与 user 服务迁移保持同步：V4 新增 status（账号启用/禁用），
                    // V1 内建 create_time。手写 DDL 漏列会让 User 实体查询报
                    // Unknown column（2026-10-11 漏 status、2026-10-14 漏 create_time，已两次 CI 红）。
                    // 这类漂移的根治方向是让 IT 直接应用 db/migration 而非手写 DDL，暂按同步处理。
                    + " status TINYINT NOT NULL DEFAULT 1,"
                    + " create_time DATETIME DEFAULT CURRENT_TIMESTAMP)"
                    + " ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
            st.execute("CREATE TABLE amz_shop ("
                    + " id BIGINT AUTO_INCREMENT PRIMARY KEY,"
                    + " shop_name VARCHAR(100) NOT NULL,"
                    + " marketplace_id VARCHAR(20) NOT NULL,"
                    + " region VARCHAR(10) NOT NULL,"
                    + " seller_id VARCHAR(30),"
                    + " spapi_refresh_token TEXT,"
                    + " spapi_client_id VARCHAR(200),"
                    + " spapi_client_secret VARCHAR(200),"
                    + " status INT DEFAULT 0,"
                    + " create_time DATETIME DEFAULT CURRENT_TIMESTAMP) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
            st.execute("CREATE TABLE amz_user_shop ("
                    + " id BIGINT AUTO_INCREMENT PRIMARY KEY,"
                    + " user_id BIGINT NOT NULL,"
                    + " shop_id BIGINT NOT NULL,"
                    + " role VARCHAR(20) DEFAULT 'OPERATOR',"
                    + " INDEX idx_user (user_id), INDEX idx_shop (shop_id))"
                    + " ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        }

        UnpooledDataSource ds = new UnpooledDataSource(
                "com.mysql.cj.jdbc.Driver", serverBase + "/" + SCHEMA + suffix, USER, PASSWORD);
        Environment env = new Environment("it", new JdbcTransactionFactory(), ds);
        MybatisConfiguration cfg = new MybatisConfiguration();
        cfg.setEnvironment(env);
        cfg.addMapper(ShopMapper.class);
        cfg.addMapper(UserShopMapper.class);
        cfg.addMapper(UserMapper.class);
        factory = new MybatisSqlSessionFactoryBuilder().build(cfg);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(cfg, ""), Shop.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(cfg, ""), UserShop.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(cfg, ""), User.class);
    }

    @AfterEach
    void clear() {
        UserContext.clear();
    }

    @Test
    @DisplayName("真机：创建店铺 → 落库可读回；普通用户无绑定则看不到")
    void createAndListOnRealMySql() throws SQLException {
        truncate();
        ShopServiceImpl service = newService();

        UserContext.setUserId(1);
        UserContext.setRole("ADMIN");
        Shop created = service.createShop(shop("美国站主店", "ATVPDKIKX0DER", "NA"));
        assertTrue(created.getId() != null && created.getId() > 0, "自增主键应回填");

        assertEquals(1, service.listMyShops().size(), "ADMIN 应看到刚建的店");

        // 普通用户未绑定时看不到任何店
        UserContext.setUserId(77);
        UserContext.setRole("VIEWER");
        assertTrue(service.listMyShops().isEmpty(), "未绑定用户不得看到任何店铺");

        // 绑定后可见，且带店铺级角色
        UserContext.setUserId(1);
        UserContext.setRole("ADMIN");
        service.assignUser(created.getId(), 77L, "OPERATOR");

        UserContext.setUserId(77);
        UserContext.setRole("VIEWER");
        List<ShopVo> mine = service.listMyShops();
        assertEquals(1, mine.size());
        assertEquals("OPERATOR", mine.get(0).getRole());
    }

    @Test
    @DisplayName("真机：凭证状态按 amz_spapi.amz_shop_credential 判定；跨库不可用时降级为未配置")
    void credentialFlagDegradesGracefully() throws SQLException {
        truncate();
        ShopServiceImpl service = newService();

        UserContext.setUserId(1);
        UserContext.setRole("ADMIN");
        Shop created = service.createShop(shop("UK 店", "A1F83G8C2ARO7P", "EU"));

        // 本 IT 库没有 amz_spapi，跨库查询会失败 → 必须降级为 false 而不是抛异常
        List<ShopVo> shops = service.listMyShops();
        assertEquals(1, shops.size());
        assertFalse(shops.get(0).isCredentialConfigured(),
                "查不到凭证状态时按未配置处理：谎报已配置会让运营跳过真正需要的配置步骤");
    }

    @Test
    @DisplayName("真机：店铺接口拒绝写入凭证字段（唯一入口是 spapi）")
    void rejectsCredentialWrite() throws SQLException {
        truncate();
        ShopServiceImpl service = newService();
        UserContext.setUserId(1);
        UserContext.setRole("ADMIN");

        Shop withSecret = shop("店", "ATVPDKIKX0DER", "NA");
        withSecret.setSpapiClientSecret("secret");
        CodeErrorException e = assertThrows(CodeErrorException.class, () -> service.createShop(withSecret));
        assertTrue(e.getMessage().contains("凭证"));
    }

    @Test
    @DisplayName("真机：分配幂等（重复分配只更新角色，不产生重复绑定）")
    void assignIsIdempotent() throws SQLException {
        truncate();
        ShopServiceImpl service = newService();
        UserContext.setUserId(1);
        UserContext.setRole("ADMIN");
        Shop created = service.createShop(shop("店", "ATVPDKIKX0DER", "NA"));

        service.assignUser(created.getId(), 88L, "VIEWER");
        service.assignUser(created.getId(), 88L, "OPERATOR");

        try (Connection conn = openConnection();
             Statement st = conn.createStatement();
             java.sql.ResultSet rs = st.executeQuery(
                     "SELECT COUNT(*), MAX(role) FROM amz_user_shop WHERE shop_id = " + created.getId())) {
            rs.next();
            assertEquals(1, rs.getInt(1), "重复分配必须是更新而不是插入新行");
            assertEquals("OPERATOR", rs.getString(2));
        }
    }

    // ============ 辅助 ============

    private void truncate() throws SQLException {
        try (Connection conn = openConnection(); Statement st = conn.createStatement()) {
            st.execute("DELETE FROM amz_shop");
            st.execute("DELETE FROM amz_user_shop");
            st.execute("DELETE FROM amz_user");
            // assignUser 会校验目标用户存在：这里种一个真实用户行，
            // 否则测的是「用户不存在」分支而不是分配语义
            st.execute("INSERT INTO amz_user (id, phone, nickname, role) VALUES (77, '13800000077', '运营甲', 'VIEWER')");
            st.execute("INSERT INTO amz_user (id, phone, nickname, role) VALUES (88, '13800000088', '运营乙', 'VIEWER')");
        }
    }

    private ShopServiceImpl newService() {
        SqlSession session = factory.openSession(true);
        ShopServiceImpl service = new ShopServiceImpl();
        ReflectionTestUtils.setField(service, "shopMapper", session.getMapper(ShopMapper.class));
        ReflectionTestUtils.setField(service, "userShopMapper", session.getMapper(UserShopMapper.class));
        ReflectionTestUtils.setField(service, "userMapper", session.getMapper(UserMapper.class));
        // 真实 JdbcTemplate 指向 IT 库：跨库查 amz_spapi 必然失败 → 走降级分支（本 IT 要验证的正是它）
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("com.mysql.cj.jdbc.Driver");
        ds.setUrl(jdbcUrl());
        ds.setUsername(USER);
        ds.setPassword(PASSWORD);
        ReflectionTestUtils.setField(service, "jdbcTemplate", new JdbcTemplate(ds));
        return service;
    }

    private Shop shop(String name, String marketplace, String region) {
        Shop shop = new Shop();
        shop.setShopName(name);
        shop.setMarketplaceId(marketplace);
        shop.setRegion(region);
        shop.setStatus(0);
        return shop;
    }

    private Connection openConnection() throws SQLException {
        return DriverManager.getConnection(jdbcUrl(), USER, PASSWORD);
    }

    private String jdbcUrl() {
        String base = BASE_URL;
        int query = base.indexOf('?');
        String suffix = query < 0 ? "" : base.substring(query);
        String head = query < 0 ? base : base.substring(0, query);
        return head.substring(0, head.lastIndexOf('/')) + "/" + SCHEMA + suffix;
    }

    private static void assertGuard() {
        assertTrue(SCHEMA.endsWith("_shop_it") && !SCHEMA.endsWith("_fwit"), "IT 库名护栏");
        String business = businessDbOf(BASE_URL);
        assertFalse(SCHEMA.equals(business), "IT 库不得与业务库同名");
    }

    private static String businessDbOf(String jdbcUrl) {
        int query = jdbcUrl.indexOf('?');
        String head = query < 0 ? jdbcUrl : jdbcUrl.substring(0, query);
        return head.substring(head.lastIndexOf('/') + 1);
    }
}
