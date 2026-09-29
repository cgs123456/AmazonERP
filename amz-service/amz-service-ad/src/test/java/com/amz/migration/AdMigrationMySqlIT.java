package com.amz.migration;

import com.amz.mapper.AdAsinKeywordMapper;
import com.amz.mapper.AdCampaignExtMapper;
import com.amz.mapper.AdDailyReportMapper;
import com.amz.mapper.AdKeywordMapper;
import com.amz.mapper.ConvertingTermMapper;
import com.amz.model.AdAsinKeyword;
import com.amz.model.AdCampaignExt;
import com.amz.model.AdDailyReport;
import com.amz.model.AdKeyword;
import com.amz.model.ConvertingTerm;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 真实 MySQL 8 的 Flyway + Mapper 集成验收。
 * <p>
 * 默认不运行；提供 AD_MYSQL_IT_URL/AD_MYSQL_IT_USER/AD_MYSQL_IT_PASSWORD 后由 Maven 显式执行。
 */
@EnabledIfEnvironmentVariable(named = "AD_MYSQL_IT_URL", matches = ".+")
class AdMigrationMySqlIT {

    private static final String URL = System.getenv("AD_MYSQL_IT_URL");
    private static final String USER = System.getenv().getOrDefault("AD_MYSQL_IT_USER", "root");
    private static final String PASSWORD = System.getenv().getOrDefault("AD_MYSQL_IT_PASSWORD", "");

    /**
     * 让这个 IT 能自举：CI 的 mysql:8.0 service 只给了一个空实例，没有 amz_ad_it 库，
     * 而连一个不存在的库会直接失败，所以先用不带库名的连接建库。
     * 随后确认它是空的——Flyway 断言 V1 必须真的执行，库里有残留断言就没意义了。
     * 本地重复执行时设 AD_MYSQL_IT_RESET=true 自动 DROP+CREATE。
     */
    @BeforeAll
    static void prepareSchema() throws SQLException {
        String schema = schemaOf(URL);
        try (Connection connection = DriverManager.getConnection(serverUrlOf(URL), USER, PASSWORD);
             Statement statement = connection.createStatement()) {
            if (Boolean.parseBoolean(System.getenv().getOrDefault("AD_MYSQL_IT_RESET", "false"))) {
                statement.executeUpdate("DROP DATABASE IF EXISTS `" + schema + "`");
            }
            statement.executeUpdate("CREATE DATABASE IF NOT EXISTS `" + schema
                    + "` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
        }
        try (Connection connection = DriverManager.getConnection(URL, USER, PASSWORD)) {
            long tables = scalarLong(connection, "SELECT COUNT(*) FROM information_schema.tables "
                    + "WHERE table_schema = '" + schema + "'");
            assertEquals(0L, tables, "IT 需要空库（Flyway 必须从 V1 起跑）：schema=" + schema
                    + " 已有 " + tables + " 张表。请先 DROP DATABASE " + schema
                    + "，或设置 AD_MYSQL_IT_RESET=true");
        }
    }

    @Test
    @DisplayName("V1-V7 在 MySQL 8 执行，租户回填、唯一键迁移和 Mapper 原子 upsert 均生效")
    void migrationsAndUpsertsWorkOnMySql8() throws Exception {
        MigrateResult v1 = flyway("1").migrate();
        assertTrue(v1.migrationsExecuted >= 1, "V1 should execute on a fresh database");

        try (Connection connection = DriverManager.getConnection(URL, USER, PASSWORD)) {
            execute(connection, "INSERT INTO amz_ad_campaign_ext "
                    + "(shop_id, campaign_id, campaign_name, ad_type, status) VALUES "
                    + "(101, 'c1', 'old-name', 'SP', 'ENABLED'), "
                    + "(101, 'c1', 'new-name', 'SP', 'PAUSED')");
            assertEquals(2, scalarLong(connection,
                    "SELECT COUNT(*) FROM amz_ad_campaign_ext WHERE shop_id=101 AND campaign_id='c1'"));
        }

        MigrateResult v2 = flyway("2").migrate();
        assertTrue(v2.migrationsExecuted >= 1, "V2 should execute after V1");

        try (Connection connection = DriverManager.getConnection(URL, USER, PASSWORD)) {
            assertEquals(3, uniqueIndexColumns(connection, "amz_ad_daily_report", "uk_shop_campaign_date"));
            assertEquals(2, uniqueIndexColumns(connection, "amz_ad_campaign_ext", "uk_shop_campaign"));
            assertEquals(1, scalarLong(connection,
                    "SELECT COUNT(*) FROM amz_ad_campaign_ext WHERE shop_id=101 AND campaign_id='c1'"));
            assertEquals("new-name", scalarString(connection,
                    "SELECT campaign_name FROM amz_ad_campaign_ext WHERE shop_id=101 AND campaign_id='c1'"));
        }

        MigrateResult v3 = flyway("3").migrate();
        assertTrue(v3.migrationsExecuted >= 1, "V3 should execute after V2");
        try (Connection connection = DriverManager.getConnection(URL, USER, PASSWORD)) {
            assertEquals(1, columnCount(connection, "amz_ad_daily_report", "update_time"));
        }

        MigrateResult v4 = flyway("4").migrate();
        assertTrue(v4.migrationsExecuted >= 1, "V4 should execute after V3");

        insertLegacyAdResources();
        MigrateResult v5 = flyway("5").migrate();
        assertTrue(v5.migrationsExecuted >= 1, "V5 should execute after V4");
        assertTenantBackfill();

        insertLegacyBusinessDuplicateRows();
        MigrateResult v6 = flyway("6").migrate();
        assertTrue(v6.migrationsExecuted >= 1, "V6 should execute after V5");
        try (Connection connection = DriverManager.getConnection(URL, USER, PASSWORD)) {
            assertEquals(1, columnCount(connection, "amz_ad_keyword", "base_bid"));
        }

        MigrateResult v7 = flyway("7").migrate();
        assertTrue(v7.migrationsExecuted >= 1, "V7 should execute after V6");
        assertAdUniquenessMigration();
        assertMapperUpserts();
    }

    private void insertLegacyAdResources() throws SQLException {
        try (Connection connection = DriverManager.getConnection(URL, USER, PASSWORD)) {
            execute(connection, "INSERT INTO amz_ad_campaign_ext "
                    + "(shop_id, campaign_id, campaign_name, ad_type, status) VALUES "
                    + "(101, 'unique-campaign', 'unique', 'SP', 'ENABLED'), "
                    + "(101, 'ambiguous-campaign', 'ambiguous-a', 'SP', 'ENABLED'), "
                    + "(102, 'ambiguous-campaign', 'ambiguous-b', 'SP', 'ENABLED')");
            execute(connection, "INSERT INTO amz_ad_creative "
                    + "(campaign_id, creative_type, headline, status) VALUES "
                    + "('unique-campaign', 'IMAGE', 'unique', 'PENDING'), "
                    + "('ambiguous-campaign', 'IMAGE', 'ambiguous', 'PENDING'), "
                    + "('orphan-campaign', 'IMAGE', 'orphan', 'PENDING')");
            execute(connection, "INSERT INTO amz_ad_targeting "
                    + "(campaign_id, targeting_type, targeting_value) VALUES "
                    + "('unique-campaign', 'CONTEXTUAL', 'asin-unique'), "
                    + "('ambiguous-campaign', 'CONTEXTUAL', 'asin-ambiguous'), "
                    + "('orphan-campaign', 'CONTEXTUAL', 'asin-orphan')");
        }
    }

    private void assertTenantBackfill() throws SQLException {
        try (Connection connection = DriverManager.getConnection(URL, USER, PASSWORD)) {
            assertEquals(101L, scalarLong(connection,
                    "SELECT shop_id FROM amz_ad_creative WHERE campaign_id='unique-campaign'"));
            assertEquals(101L, scalarLong(connection,
                    "SELECT shop_id FROM amz_ad_targeting WHERE campaign_id='unique-campaign'"));
            assertEquals(1, scalarLong(connection,
                    "SELECT COUNT(*) FROM amz_ad_creative "
                            + "WHERE campaign_id='ambiguous-campaign' AND shop_id IS NULL"));
            assertEquals(1, scalarLong(connection,
                    "SELECT COUNT(*) FROM amz_ad_targeting "
                            + "WHERE campaign_id='ambiguous-campaign' AND shop_id IS NULL"));
            assertEquals(1, scalarLong(connection,
                    "SELECT COUNT(*) FROM amz_ad_creative "
                            + "WHERE campaign_id='orphan-campaign' AND shop_id IS NULL"));
            assertEquals(1, scalarLong(connection,
                    "SELECT COUNT(*) FROM amz_ad_targeting "
                            + "WHERE campaign_id='orphan-campaign' AND shop_id IS NULL"));
            assertEquals(3, indexColumns(connection, "amz_ad_creative", "idx_ad_creative_shop_campaign"));
            assertEquals(4, indexColumns(connection, "amz_ad_targeting", "idx_ad_targeting_shop_campaign"));
        }
    }

    private void insertLegacyBusinessDuplicateRows() throws SQLException {
        try (Connection connection = DriverManager.getConnection(URL, USER, PASSWORD)) {
            execute(connection, "INSERT INTO amz_ad_keyword "
                    + "(shop_id, campaign_id, keyword, match_type, bid, state) VALUES "
                    + "(101, 'c1', '  Wireless Earbuds  ', ' exact ', 1.00, 'ENABLED'), "
                    + "(101, 'c1', 'wireless earbuds', 'EXACT', 2.00, 'PAUSED')");

            execute(connection, "INSERT INTO amz_ad_converting_terms "
                    + "(shop_id, asin, search_term, campaign_id, total_orders, total_sales, total_cost, "
                    + "avg_acos, first_seen, last_seen, is_added_to_keyword, status) VALUES "
                    + "(101, ' b0002 ', ' Wireless Earbuds ', NULL, 1, 10.00, 2.50, 25.00, "
                    + "'2026-09-01', '2026-09-02', 1, 'ARCHIVED'), "
                    + "(101, ' b0001 ', 'wireless earbuds ', '   ', 2, 20.00, 5.00, 25.00, "
                    + "'2026-09-03', '2026-09-04', 0, 'ACTIVE')");

            execute(connection, "INSERT INTO amz_ad_asin_keyword "
                    + "(shop_id, asin, keyword, organic_rank, ad_rank, search_volume, relevance_score, "
                    + "is_indexed, last_checked) VALUES "
                    + "(101, ' b0001 ', ' Wireless Earbuds ', 10, 20, 100, 3.5, 1, '2026-09-01'), "
                    + "(101, 'b0001', 'wireless earbuds ', 50, 60, 800, 4.0, 0, '2026-09-02')");
        }
    }

    private void assertAdUniquenessMigration() throws SQLException {
        try (Connection connection = DriverManager.getConnection(URL, USER, PASSWORD)) {
            assertEquals(4, uniqueIndexColumns( // 4 列：shop_id, campaign_id, keyword, match_type
                    connection, "amz_ad_keyword", "uk_ad_keyword_shop_campaign_keyword_match"));
            assertEquals(1, scalarLong(connection,
                    "SELECT COUNT(*) FROM amz_ad_keyword "
                            + "WHERE shop_id=101 AND campaign_id='c1' "
                            + "AND keyword='wireless earbuds' AND match_type='EXACT'"));
            assertEquals(new BigDecimal("2.00"), scalarDecimal(connection,
                    "SELECT bid FROM amz_ad_keyword WHERE shop_id=101 AND campaign_id='c1' "
                            + "AND keyword='wireless earbuds' AND match_type='EXACT'"));
            assertEquals("PAUSED", scalarString(connection,
                    "SELECT state FROM amz_ad_keyword WHERE shop_id=101 AND campaign_id='c1' "
                            + "AND keyword='wireless earbuds' AND match_type='EXACT'"));

            assertEquals(3, uniqueIndexColumns( // 3 列：shop_id, campaign_id, search_term
                    connection, "amz_ad_converting_terms", "uk_ad_converting_shop_campaign_term"));
            assertEquals(1, scalarLong(connection,
                    "SELECT COUNT(*) FROM amz_ad_converting_terms "
                            + "WHERE shop_id=101 AND campaign_id='' AND search_term='wireless earbuds'"));
            assertEquals("B0001", scalarString(connection,
                    "SELECT asin FROM amz_ad_converting_terms "
                            + "WHERE shop_id=101 AND campaign_id='' AND search_term='wireless earbuds'"));
            assertEquals(2L, scalarLong(connection,
                    "SELECT total_orders FROM amz_ad_converting_terms "
                            + "WHERE shop_id=101 AND campaign_id='' AND search_term='wireless earbuds'"));
            assertEquals(1L, scalarLong(connection,
                    "SELECT is_added_to_keyword FROM amz_ad_converting_terms "
                            + "WHERE shop_id=101 AND campaign_id='' AND search_term='wireless earbuds'"));
            assertEquals("ARCHIVED", scalarString(connection,
                    "SELECT status FROM amz_ad_converting_terms "
                            + "WHERE shop_id=101 AND campaign_id='' AND search_term='wireless earbuds'"));
            assertEquals("NO", scalarString(connection,
                    "SELECT is_nullable FROM information_schema.columns "
                            + "WHERE table_schema=DATABASE() AND table_name='amz_ad_converting_terms' "
                            + "AND column_name='campaign_id'"));

            assertEquals(3, uniqueIndexColumns( // 3 列：shop_id, asin, keyword
                    connection, "amz_ad_asin_keyword", "uk_ad_asin_shop_asin_keyword"));
            assertEquals(1, scalarLong(connection,
                    "SELECT COUNT(*) FROM amz_ad_asin_keyword "
                            + "WHERE shop_id=101 AND asin='B0001' AND keyword='wireless earbuds'"));
            assertEquals(50L, scalarLong(connection,
                    "SELECT organic_rank FROM amz_ad_asin_keyword "
                            + "WHERE shop_id=101 AND asin='B0001' AND keyword='wireless earbuds'"));
            assertEquals(800L, scalarLong(connection,
                    "SELECT search_volume FROM amz_ad_asin_keyword "
                            + "WHERE shop_id=101 AND asin='B0001' AND keyword='wireless earbuds'"));
            assertEquals(0L, scalarLong(connection,
                    "SELECT is_indexed FROM amz_ad_asin_keyword "
                            + "WHERE shop_id=101 AND asin='B0001' AND keyword='wireless earbuds'"));
        }
    }
    private void assertMapperUpserts() throws SQLException {
        UnpooledDataSource dataSource = new UnpooledDataSource("com.mysql.cj.jdbc.Driver", URL, USER, PASSWORD);
        Configuration configuration = new Configuration(
                new Environment("ad-mysql-it", new JdbcTransactionFactory(), dataSource));
        configuration.addMapper(AdDailyReportMapper.class);
        configuration.addMapper(AdCampaignExtMapper.class);
        configuration.addMapper(AdKeywordMapper.class);
        configuration.addMapper(ConvertingTermMapper.class);
        configuration.addMapper(AdAsinKeywordMapper.class);

        try (SqlSession session = new SqlSessionFactoryBuilder().build(configuration).openSession(true)) {
            AdDailyReportMapper reportMapper = session.getMapper(AdDailyReportMapper.class);
            AdDailyReport report = new AdDailyReport();
            report.setShopId(202L);
            report.setCampaignId("c2");
            report.setAdType("SP");
            report.setReportDate(LocalDate.of(2026, 9, 24));
            report.setImpressions(100L);
            report.setClicks(10L);
            report.setCost(new BigDecimal("5.00"));
            report.setSales(new BigDecimal("20.00"));
            report.setOrders(2);
            report.setUnits(2);
            report.setAcos(new BigDecimal("25.00"));
            report.setRoas(new BigDecimal("4.00"));
            report.setCr(new BigDecimal("20.00"));
            report.setCtr(new BigDecimal("10.00"));
            report.setCpc(new BigDecimal("0.50"));
            assertTrue(reportMapper.upsert(report) > 0, "MySQL upsert must affect at least one row");

            report.setImpressions(200L);
            report.setClicks(20L);
            report.setCost(new BigDecimal("8.00"));
            report.setSales(new BigDecimal("32.00"));
            report.setOrders(3);
            report.setUnits(3);
            report.setAcos(new BigDecimal("25.00"));
            report.setRoas(new BigDecimal("4.00"));
            assertTrue(reportMapper.upsert(report) > 0, "MySQL upsert must affect at least one row");

            AdCampaignExtMapper campaignMapper = session.getMapper(AdCampaignExtMapper.class);
            AdCampaignExt campaign = new AdCampaignExt();
            campaign.setShopId(202L);
            campaign.setCampaignId("c2");
            campaign.setCampaignName("metadata-v1");
            campaign.setAdType("SP");
            campaign.setCampaignType("SPONSORED_PRODUCTS");
            campaign.setBudget(new BigDecimal("10.00"));
            campaign.setBudgetType("DAILY");
            campaign.setBiddingStrategy("FOR_SALES");
            campaign.setStatus("ENABLED");
            assertTrue(campaignMapper.upsertMetadata(campaign) > 0, "MySQL metadata upsert must affect at least one row");

            try (Connection connection = DriverManager.getConnection(URL, USER, PASSWORD)) {
                execute(connection, "UPDATE amz_ad_campaign_ext SET impressions=100, clicks=10, "
                        + "spend=5.00, sales=20.00, orders=2, acos=25.00, roas=4.00 "
                        + "WHERE shop_id=202 AND campaign_id='c2'");
            }

            campaign.setCampaignName("metadata-v2");
            campaign.setStatus("PAUSED");
            assertTrue(campaignMapper.upsertMetadata(campaign) > 0, "MySQL metadata upsert must affect at least one row");

            AdKeywordMapper keywordMapper = session.getMapper(AdKeywordMapper.class);
            AdKeyword keyword = new AdKeyword();
            keyword.setCampaignId("baseline-campaign");
            keyword.setShopId(202L);
            keyword.setKeyword("wireless earbuds");
            keyword.setMatchType("EXACT");
            keyword.setBid(new BigDecimal("1.25"));
            keyword.setBaseBid(new BigDecimal("1.00"));
            keyword.setState("ENABLED");
            assertTrue(keywordMapper.insertBaseBidRows(List.of(keyword)) > 0,
                    "MySQL keyword baseline insert must affect at least one row");

            AdKeyword concurrentClaim = new AdKeyword();
            concurrentClaim.setCampaignId("baseline-campaign");
            concurrentClaim.setShopId(202L);
            concurrentClaim.setKeyword("wireless earbuds");
            concurrentClaim.setMatchType("EXACT");
            concurrentClaim.setBid(new BigDecimal("2.00"));
            concurrentClaim.setBaseBid(new BigDecimal("2.00"));
            concurrentClaim.setState("PAUSED");
            assertTrue(keywordMapper.insertBaseBidRows(List.of(concurrentClaim)) > 0,
                    "MySQL keyword duplicate claim must affect at least one row");

            ConvertingTermMapper convertingTermMapper = session.getMapper(ConvertingTermMapper.class);
            ConvertingTerm convertingTerm = new ConvertingTerm();
            convertingTerm.setShopId(202L);
            convertingTerm.setAsin("B0TEST0001");
            convertingTerm.setSearchTerm("yoga mat");
            convertingTerm.setCampaignId("converting-campaign");
            convertingTerm.setTotalOrders(2);
            convertingTerm.setTotalSales(new BigDecimal("40.00"));
            convertingTerm.setTotalCost(new BigDecimal("10.00"));
            convertingTerm.setAvgAcos(new BigDecimal("25.00"));
            convertingTerm.setFirstSeen(LocalDate.of(2026, 9, 1));
            convertingTerm.setLastSeen(LocalDate.of(2026, 9, 2));
            convertingTerm.setIsAddedToKeyword(1);
            convertingTerm.setStatus("ARCHIVED");
            assertTrue(convertingTermMapper.upsertBatch(List.of(convertingTerm)) > 0,
                    "MySQL converting-term insert must affect at least one row");

            ConvertingTerm refreshedTerm = new ConvertingTerm();
            refreshedTerm.setShopId(202L);
            refreshedTerm.setAsin("B0TEST0001");
            refreshedTerm.setSearchTerm("yoga mat");
            refreshedTerm.setCampaignId("converting-campaign");
            refreshedTerm.setTotalOrders(5);
            refreshedTerm.setTotalSales(new BigDecimal("100.00"));
            refreshedTerm.setTotalCost(new BigDecimal("20.00"));
            refreshedTerm.setAvgAcos(new BigDecimal("20.00"));
            refreshedTerm.setFirstSeen(LocalDate.of(2026, 9, 1));
            refreshedTerm.setLastSeen(LocalDate.of(2026, 9, 5));
            refreshedTerm.setIsAddedToKeyword(0);
            refreshedTerm.setStatus("ACTIVE");
            assertTrue(convertingTermMapper.upsertBatch(List.of(refreshedTerm)) > 0,
                    "MySQL converting-term refresh must affect at least one row");

            ConvertingTerm otherTenantTerm = new ConvertingTerm();
            otherTenantTerm.setShopId(203L);
            otherTenantTerm.setAsin("B0TEST0001");
            otherTenantTerm.setSearchTerm("yoga mat");
            otherTenantTerm.setCampaignId("converting-campaign");
            otherTenantTerm.setTotalOrders(99);
            otherTenantTerm.setTotalSales(new BigDecimal("999.00"));
            otherTenantTerm.setTotalCost(new BigDecimal("99.00"));
            otherTenantTerm.setAvgAcos(new BigDecimal("9.00"));
            otherTenantTerm.setFirstSeen(LocalDate.of(2026, 9, 1));
            otherTenantTerm.setLastSeen(LocalDate.of(2026, 9, 5));
            otherTenantTerm.setIsAddedToKeyword(0);
            otherTenantTerm.setStatus("ACTIVE");
            convertingTermMapper.upsertBatch(List.of(otherTenantTerm));

            List<ConvertingTerm> loadedTerms = convertingTermMapper.selectByKeys(
                    202L, List.of("converting-campaign"), List.of("yoga mat"));
            assertEquals(1, loadedTerms.size(), "converting-term readback must be tenant scoped");
            assertEquals(5, loadedTerms.get(0).getTotalOrders());
            assertEquals(1, loadedTerms.get(0).getIsAddedToKeyword());
            assertEquals("ARCHIVED", loadedTerms.get(0).getStatus());

            AdAsinKeywordMapper asinKeywordMapper = session.getMapper(AdAsinKeywordMapper.class);
            AdAsinKeyword asinKeyword = new AdAsinKeyword();
            asinKeyword.setShopId(202L);
            asinKeyword.setAsin("B0TEST0002");
            asinKeyword.setKeyword("ergonomic mouse");
            asinKeyword.setOrganicRank(9);
            asinKeyword.setAdRank(7);
            asinKeyword.setSearchVolume(100);
            asinKeyword.setRelevanceScore(new BigDecimal("3.5"));
            asinKeyword.setIsIndexed(1);
            asinKeyword.setLastChecked(LocalDate.of(2026, 9, 1));
            assertTrue(asinKeywordMapper.upsertBatch(List.of(asinKeyword)) > 0,
                    "MySQL ASIN-keyword insert must affect at least one row");

            asinKeyword.setOrganicRank(5);
            asinKeyword.setSearchVolume(200);
            asinKeyword.setIsIndexed(0);
            asinKeyword.setLastChecked(LocalDate.of(2026, 9, 6));
            assertTrue(asinKeywordMapper.upsertBatch(List.of(asinKeyword)) > 0,
                    "MySQL ASIN-keyword refresh must affect at least one row");

            AdAsinKeyword otherTenantAsinKeyword = new AdAsinKeyword();
            otherTenantAsinKeyword.setShopId(203L);
            otherTenantAsinKeyword.setAsin("B0TEST0002");
            otherTenantAsinKeyword.setKeyword("ergonomic mouse");
            otherTenantAsinKeyword.setOrganicRank(99);
            otherTenantAsinKeyword.setAdRank(99);
            otherTenantAsinKeyword.setSearchVolume(999);
            otherTenantAsinKeyword.setRelevanceScore(new BigDecimal("5.0"));
            otherTenantAsinKeyword.setIsIndexed(1);
            otherTenantAsinKeyword.setLastChecked(LocalDate.of(2026, 9, 6));
            asinKeywordMapper.upsertBatch(List.of(otherTenantAsinKeyword));

            List<AdAsinKeyword> loadedAsinKeywords = asinKeywordMapper.selectByKeys(
                    202L, List.of("B0TEST0002"), List.of("ergonomic mouse"));
            assertEquals(1, loadedAsinKeywords.size(), "ASIN-keyword readback must be tenant scoped");
            assertEquals(5, loadedAsinKeywords.get(0).getOrganicRank());
            assertEquals(200, loadedAsinKeywords.get(0).getSearchVolume());
            assertEquals(0, loadedAsinKeywords.get(0).getIsIndexed());
        }

        try (Connection connection = DriverManager.getConnection(URL, USER, PASSWORD)) {
            assertEquals(1, scalarLong(connection,
                    "SELECT COUNT(*) FROM amz_ad_daily_report WHERE shop_id=202 AND campaign_id='c2'"));
            assertEquals(200L, scalarLong(connection,
                    "SELECT impressions FROM amz_ad_daily_report WHERE shop_id=202 AND campaign_id='c2'"));
            assertEquals(3L, scalarLong(connection,
                    "SELECT orders FROM amz_ad_daily_report WHERE shop_id=202 AND campaign_id='c2'"));

            assertEquals(1, scalarLong(connection,
                    "SELECT COUNT(*) FROM amz_ad_campaign_ext WHERE shop_id=202 AND campaign_id='c2'"));
            assertEquals("metadata-v2", scalarString(connection,
                    "SELECT campaign_name FROM amz_ad_campaign_ext WHERE shop_id=202 AND campaign_id='c2'"));
            assertEquals(100L, scalarLong(connection,
                    "SELECT impressions FROM amz_ad_campaign_ext WHERE shop_id=202 AND campaign_id='c2'"));
            assertEquals(new BigDecimal("20.00"), scalarDecimal(connection,
                    "SELECT sales FROM amz_ad_campaign_ext WHERE shop_id=202 AND campaign_id='c2'"));
        }
    }

    /** 从 jdbc:mysql://host:port/schema?params 取出库名。 */
    private static String schemaOf(String url) {
        Matcher matcher = Pattern.compile("^jdbc:mysql://[^/?]+/([^/?]+)(\\?.*)?$").matcher(url);
        assertTrue(matcher.matches(),
                "AD_MYSQL_IT_URL 必须形如 jdbc:mysql://host:port/schema?params：" + url);
        return matcher.group(1);
    }

    /** 去掉库名后的服务端连接串，专门用于 CREATE DATABASE。 */
    private static String serverUrlOf(String url) {
        Matcher matcher = Pattern.compile("^(jdbc:mysql://[^/?]+)/([^/?]+)(\\?.*)?$").matcher(url);
        assertTrue(matcher.matches(),
                "AD_MYSQL_IT_URL 必须形如 jdbc:mysql://host:port/schema?params：" + url);
        return matcher.group(1) + "/" + (matcher.group(3) == null ? "" : matcher.group(3));
    }

    private static Flyway flyway(String target) {
        return Flyway.configure()
                .dataSource(URL, USER, PASSWORD)
                .locations("classpath:db/migration")
                // 与 amz-service-ad 的 application.yml 一致：本 IT 先 DROP+CREATE 库，
                // 走的是生产空库路径，因此 false 与 true 结果相同（V1..V7 全部执行）。
                .baselineOnMigrate(false)
                .baselineVersion("1")
                .target(target)
                .cleanDisabled(true)
                .load();
    }

    private static int indexColumns(Connection connection, String table, String index) throws SQLException {
        String sql = "SELECT COUNT(*) FROM information_schema.statistics "
                + "WHERE table_schema = DATABASE() AND table_name = '" + table + "' "
                + "AND index_name = '" + index + "'";
        return Math.toIntExact(scalarLong(connection, sql));
    }

    private static int uniqueIndexColumns(Connection connection, String table, String index) throws SQLException {
        String sql = "SELECT COUNT(*) FROM information_schema.statistics "
                + "WHERE table_schema = DATABASE() AND table_name = '" + table + "' "
                + "AND index_name = '" + index + "' AND non_unique = 0";
        return Math.toIntExact(scalarLong(connection, sql));
    }

    private static int columnCount(Connection connection, String table, String column) throws SQLException {
        String sql = "SELECT COUNT(*) FROM information_schema.columns "
                + "WHERE table_schema = DATABASE() AND table_name = '" + table + "' "
                + "AND column_name = '" + column + "'";
        return Math.toIntExact(scalarLong(connection, sql));
    }

    private static void execute(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate(sql);
        }
    }

    private static long scalarLong(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(sql)) {
            assertTrue(resultSet.next(), "query should return a row: " + sql);
            return resultSet.getLong(1);
        }
    }

    private static String scalarString(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(sql)) {
            assertTrue(resultSet.next(), "query should return a row: " + sql);
            return resultSet.getString(1);
        }
    }

    private static BigDecimal scalarDecimal(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(sql)) {
            assertTrue(resultSet.next(), "query should return a row: " + sql);
            return resultSet.getBigDecimal(1);
        }
    }
}

