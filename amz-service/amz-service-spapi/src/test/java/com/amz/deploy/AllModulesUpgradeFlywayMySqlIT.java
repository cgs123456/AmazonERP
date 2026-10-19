package com.amz.deploy;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 真实 MySQL 8 上的 Flyway「升级库」路径验收。
 *
 * <p>已有 {@link AllModulesFlywayMySqlIT} 只证明空库能依次执行全部迁移；
 * {@code BareSqlBuiltSchemaFlywayStartIT} 只证明裸 SQL 建出的库能被 baseline。
 * 两者都不能证明「已经执行过旧版本迁移、且库里有真实旧数据」时后续迁移会保留数据并完成
 * 列改名、回填、索引替换。本 IT 专门覆盖 V1 之后仍有多版本迁移的 8 个库：
 * order、ai、finance、procurement、product、logistics、multiplatform、spapi。
 *
 * <p>AD 的 V1-V7 升级路径已有 {@code AdMigrationMySqlIT}，不在这里重复。
 * 只有 V1 的库没有可升级的后续版本，由裸 SQL baseline IT 覆盖。
 *
 * <p>安全性：所有测试库都必须带 {@code _upit} 后缀，测试会 DROP + CREATE 该后缀库，
 * 不会触碰业务库。未设置 {@code FLYWAY_ALL_IT_URL} 时整类跳过。
 */
@EnabledIfEnvironmentVariable(named = "FLYWAY_ALL_IT_URL", matches = ".+")
class AllModulesUpgradeFlywayMySqlIT {

    private static final Path ROOT = findRepoRoot();
    private static final String SCHEMA_SUFFIX = "_upit";
    private static final String BASE_URL = System.getenv("FLYWAY_ALL_IT_URL");
    private static final String USER = System.getenv().getOrDefault("FLYWAY_ALL_IT_USER", "root");
    private static final String PASSWORD = System.getenv().getOrDefault("FLYWAY_ALL_IT_PASSWORD", "");

    private static final Pattern JDBC_BASE = Pattern.compile("^(jdbc:mysql://[^/?]+)/[^/?]*(\\?.*)?$");

    @Test
    @DisplayName("order V1 -> V5：宽主键、复合订单身份、订单明细和旧索引替换")
    void orderUpgradeKeepsRowsAndMigratesIdentity() throws Exception {
        String schema = resetSchema("amz_order");
        assertMigration(migrate(schema, "amz-service-order", "1"), 1);

        try (Connection connection = open(schema)) {
            execute(connection, "INSERT INTO amz_order "
                    + "(id, product_id, quantity, final_price, user_id, shop_id, "
                    + "amazon_order_id, marketplace_id, order_status) VALUES "
                    + "(1, 2147483647, 2, 12.34, 2147483647, 10, "
                    + "'111-0000000-0000001', 'ATVPDKIKX0DER', 'Shipped')");
            execute(connection, "INSERT INTO amz_shipment_routing "
                    + "(shop_id, order_id, sku, quantity) VALUES "
                    + "(10, '111-0000000-0000001', 'SKU-1', 1)");
        }

        assertMigration(migrate(schema, "amz-service-order", "5"), 4);

        try (Connection connection = open(schema)) {
            assertEquals("bigint", columnDataType(connection, "amz_order", "product_id"));
            assertEquals("bigint", columnDataType(connection, "amz_order", "user_id"));
            assertEquals(1L, scalarLong(connection,
                    "SELECT COUNT(*) FROM amz_order WHERE id = 1 AND amazon_order_id = "
                            + "'111-0000000-0000001'"));
            assertEquals(3, indexColumnCount(connection, "amz_order", "uk_shop_market_order"));
            assertEquals(3, uniqueIndexColumnCount(connection, "amz_order", "uk_shop_market_order"));
            assertEquals(2, indexColumnCount(connection, "amz_order", "idx_shop_purchase_date"));
            assertFalse(hasIndex(connection, "amz_order", "uk_amazon_order"));
            assertFalse(hasIndex(connection, "amz_order", "idx_shop"));

            assertTrue(columnExists(connection, "amz_shipment_routing", "amazon_order_id"));
            assertFalse(columnExists(connection, "amz_shipment_routing", "order_id"));
            assertEquals("111-0000000-0000001", scalarString(connection,
                    "SELECT amazon_order_id FROM amz_shipment_routing WHERE id = 1"));
            assertFalse(hasIndex(connection, "amz_shipment_routing", "idx_order"));
            assertTrue(hasIndex(connection, "amz_shipment_routing", "idx_amazon_order"));

            execute(connection, "INSERT INTO amz_order "
                    + "(id, shop_id, amazon_order_id, marketplace_id) VALUES "
                    + "(2, 11, '111-0000000-0000001', 'ATVPDKIKX0DER')");
            assertEquals(2L, scalarLong(connection,
                    "SELECT COUNT(*) FROM amz_order WHERE amazon_order_id = '111-0000000-0000001'"));

            assertTrue(tableExists(connection, "amz_order_item"));
            assertEquals(3, uniqueIndexColumnCount(connection, "amz_order_item", "uk_order_item"));
            execute(connection, "INSERT INTO amz_order_item "
                    + "(id, shop_id, amazon_order_id, amazon_order_item_id, quantity) VALUES "
                    + "(100, 10, '111-0000000-0000001', 'item-1', 1)");
            assertEquals(1L, scalarLong(connection, "SELECT COUNT(*) FROM amz_order_item WHERE id = 100"));

            // V7 删除 coupon_id 悬空列：amz_coupon 表已在 product V5 删除，该列再无引用目标。
            // 断言「列已删」而不是「列类型是什么」——删列若被回滚，这里必须红。
            // 本次只执行 V6/V7 两个（前面已 migrate 到 "5"），所以期望 2 而不是 7。
            assertMigration(migrate(schema, "amz-service-order", "7"), 2);
            assertFalse(columnExists(connection, "amz_order", "coupon_id"));
            // 删列不影响既有行与其余列
            assertEquals(1L, scalarLong(connection, "SELECT COUNT(*) FROM amz_order WHERE id = 1"));
            assertEquals("bigint", columnDataType(connection, "amz_order", "product_id"));
        }
    }

    @Test
    @DisplayName("ai V1 -> V2：新增 Agent 评估日志表且保留旧数据")
    void aiUpgradeAddsAgentEvalLogWithoutTouchingLegacyData() throws Exception {
        String schema = resetSchema("amz_ai");
        assertMigration(migrate(schema, "amz-service-ai", "1"), 1);

        try (Connection connection = open(schema)) {
            execute(connection, "INSERT INTO amz_user_preference "
                    + "(user_id, nickname, preferred_shop_id, preferred_category, language) VALUES "
                    + "(1, 'Legacy user', 1, 'Electronics', 'ZH')");
        }

        assertMigration(migrate(schema, "amz-service-ai", "2"), 1);

        try (Connection connection = open(schema)) {
            assertTrue(tableExists(connection, "amz_agent_eval_log"));
            assertTrue(hasIndex(connection, "amz_agent_eval_log", "idx_run_time"));
            assertEquals(1L, scalarLong(connection,
                    "SELECT COUNT(*) FROM amz_user_preference WHERE user_id = 1 AND nickname = 'Legacy user'"));
            execute(connection, "INSERT INTO amz_agent_eval_log "
                    + "(run_time, mode, total_cases, passed_count, pass_rate, llm_evaluated_count) VALUES "
                    + "(CURRENT_TIMESTAMP, 'both', 2, 1, 0.5000, 2)");
            assertEquals(1L, scalarLong(connection, "SELECT COUNT(*) FROM amz_agent_eval_log"));
        }
    }
    @Test
    @DisplayName("finance V1 -> V7：结算/回款订单号列改名并保留旧值")
    void financeUpgradeRenamesOrderNumberColumns() throws Exception {
        String schema = resetSchema("amz_finance");
        assertMigration(migrate(schema, "amz-service-finance", "1"), 1);
        assertMigration(migrate(schema, "amz-service-finance", "6"), 5);

        try (Connection connection = open(schema)) {
            execute(connection, "INSERT INTO amz_settlement_detail "
                    + "(shop_id, settlement_id, order_id, sku, transaction_type, amount, row_key) VALUES "
                    + "(1, 'settlement-1', '111-0000000-0000001', 'SKU-1', 'Order', 10.00, "
                    + "'0123456789abcdef0123456789abcdef')");
            execute(connection, "INSERT INTO amz_payment_collection "
                    + "(shop_id, order_id, currency, receivable) VALUES "
                    + "(1, '111-0000000-0000001', 'USD', 10.00)");
        }

        assertMigration(migrate(schema, "amz-service-finance", "7"), 1);

        try (Connection connection = open(schema)) {
            assertTrue(columnExists(connection, "amz_settlement_detail", "amazon_order_id"));
            assertFalse(columnExists(connection, "amz_settlement_detail", "order_id"));
            assertEquals("111-0000000-0000001", scalarString(connection,
                    "SELECT amazon_order_id FROM amz_settlement_detail WHERE shop_id = 1"));
            assertTrue(hasIndex(connection, "amz_settlement_detail", "idx_shop_amazon_order"));
            assertFalse(hasIndex(connection, "amz_settlement_detail", "idx_shop_order"));

            assertTrue(columnExists(connection, "amz_payment_collection", "amazon_order_id"));
            assertFalse(columnExists(connection, "amz_payment_collection", "order_id"));
            assertEquals("111-0000000-0000001", scalarString(connection,
                    "SELECT amazon_order_id FROM amz_payment_collection WHERE shop_id = 1"));
            assertEquals(2, indexColumnCount(connection, "amz_payment_collection", "uk_shop_amazon_order"));
            assertEquals(2, uniqueIndexColumnCount(connection, "amz_payment_collection", "uk_shop_amazon_order"));
            assertTrue(hasIndex(connection, "amz_accounting_voucher", "idx_kingdee_claim"));
        }
    }

    @Test
    @DisplayName("procurement V1 -> V2：旧批次保留且 FBA 签收键唯一")
    void procurementUpgradeAddsFbaReceiptIdempotency() throws Exception {
        String schema = resetSchema("amz_procurement");
        assertMigration(migrate(schema, "amz-service-procurement", "1"), 1);

        try (Connection connection = open(schema)) {
            execute(connection, "INSERT INTO amz_inventory_batch "
                    + "(shop_id, batch_no, sku, quantity, available_quantity, unit_cost, inbound_date) VALUES "
                    + "(1, 'BATCH-1', 'SKU-1', 10, 10, 5.00, '2026-01-01')");
        }

        assertMigration(migrate(schema, "amz-service-procurement", "2"), 1);

        try (Connection connection = open(schema)) {
            assertTrue(columnExists(connection, "amz_inventory_batch", "shipment_item_id"));
            assertEquals(1L, scalarLong(connection,
                    "SELECT COUNT(*) FROM amz_inventory_batch WHERE batch_no = 'BATCH-1' "
                            + "AND shipment_item_id IS NULL"));
            assertEquals(1, uniqueIndexColumnCount(connection,
                    "amz_inventory_batch", "uk_inventory_batch_shipment_item"));

            execute(connection, "INSERT INTO amz_inventory_batch "
                    + "(shop_id, batch_no, sku, quantity, available_quantity, unit_cost, inbound_date) VALUES "
                    + "(1, 'BATCH-2', 'SKU-2', 1, 1, 1.00, '2026-01-02')");
            execute(connection, "INSERT INTO amz_inventory_batch "
                    + "(shop_id, batch_no, sku, quantity, available_quantity, unit_cost, inbound_date, "
                    + "shipment_item_id) VALUES (1, 'BATCH-3', 'SKU-3', 1, 1, 1.00, '2026-01-03', 42)");
            SQLException duplicate = assertThrows(SQLException.class, () -> execute(connection,
                    "INSERT INTO amz_inventory_batch "
                            + "(shop_id, batch_no, sku, quantity, available_quantity, unit_cost, "
                            + "inbound_date, shipment_item_id) VALUES "
                            + "(1, 'BATCH-4', 'SKU-4', 1, 1, 1.00, '2026-01-04', 42)"));
            assertTrue(duplicate.getMessage().contains("Duplicate entry"), duplicate.getMessage());
        }
    }

    @Test
    @DisplayName("product V1 -> V4：productType、轮询回填和 PARTIAL 状态")
    void productUpgradeBackfillsPollingAndStatus() throws Exception {
        String schema = resetSchema("amz_product");
        assertMigration(migrate(schema, "amz-service-product", "1"), 1);

        try (Connection connection = open(schema)) {
            execute(connection, "INSERT INTO amz_product "
                    + "(id, shop_id, sku, marketplace_id, title, category) VALUES "
                    + "(1, 1, 'SKU-1', 'ATVPDKIKX0DER', 'Old title', 'Electronics')");
            execute(connection, "INSERT INTO amz_listing_copy_task "
                    + "(id, shop_id, source_marketplace_id, target_marketplace_id, sku, status, "
                    + "feed_submission_id) VALUES "
                    + "(1, 1, 'ATVPDKIKX0DER', 'A1PA6795UKMFR9', 'SKU-1', 'SUBMITTED', 'feed-1')");
        }

        assertMigration(migrate(schema, "amz-service-product", "3"), 2);

        try (Connection connection = open(schema)) {
            assertTrue(columnExists(connection, "amz_product", "product_type"));
            assertTrue(columnExists(connection, "amz_listing_copy_task", "product_type"));
            assertEquals("0", scalarString(connection,
                    "SELECT poll_attempts FROM amz_listing_copy_task WHERE id = 1"));
            assertNotNull(scalarString(connection,
                    "SELECT next_poll_time FROM amz_listing_copy_task WHERE id = 1"));
            assertNotNull(scalarString(connection,
                    "SELECT poll_deadline FROM amz_listing_copy_task WHERE id = 1"));
            assertEquals(300L, scalarLong(connection,
                    "SELECT TIMESTAMPDIFF(SECOND, next_poll_time, poll_deadline) "
                            + "FROM amz_listing_copy_task WHERE id = 1"));
            assertTrue(hasIndex(connection, "amz_listing_copy_task", "idx_listing_copy_poll_due"));
        }

        assertMigration(migrate(schema, "amz-service-product", "4"), 1);

        try (Connection connection = open(schema)) {
            assertEquals("varchar", columnDataType(connection, "amz_listing_copy_task", "status"));
            assertEquals(20L, columnLength(connection, "amz_listing_copy_task", "status"));
            assertEquals("PENDING", columnDefault(connection, "amz_listing_copy_task", "status"));
            assertTrue(columnComment(connection, "amz_listing_copy_task", "status").contains("PARTIAL"));
            assertEquals("SUBMITTED", scalarString(connection,
                    "SELECT status FROM amz_listing_copy_task WHERE id = 1"));
        }
    }

    @Test
    @DisplayName("logistics V1 -> V5：轨迹来源、同步时间与查询索引升级")
    void logisticsUpgradeAddsTrackingMetadataAndIndexes() throws Exception {
        String schema = resetSchema("amz_logistics");
        assertMigration(migrate(schema, "amz-service-logistics", "1"), 1);

        try (Connection connection = open(schema)) {
            execute(connection, "INSERT INTO amz_shipment "
                    + "(shipment_no, shop_id, master_tracking_no, status) VALUES "
                    + "('SHP-1', 1, 'TRACK-1', 'IN_TRANSIT')");
            execute(connection, "INSERT INTO amz_tracking_event "
                    + "(shipment_id, event_status, event_time) VALUES "
                    + "(1, 'IN_TRANSIT', '2026-01-01T00:00:00Z')");
        }

        assertMigration(migrate(schema, "amz-service-logistics", "5"), 4);

        try (Connection connection = open(schema)) {
            assertTrue(columnExists(connection, "amz_tracking_event", "source"));
            assertTrue(columnExists(connection, "amz_tracking_event", "raw_status"));
            assertTrue(columnExists(connection, "amz_shipment", "data_source"));
            assertTrue(columnExists(connection, "amz_shipment", "last_track_time"));
            assertEquals("AUTO", scalarString(connection,
                    "SELECT data_source FROM amz_shipment WHERE shipment_no = 'SHP-1'"));
            assertEquals("2026-01-01T00:00:00Z", scalarString(connection,
                    "SELECT event_time FROM amz_tracking_event WHERE shipment_id = 1"));
            assertTrue(hasIndex(connection, "amz_tracking_event", "idx_shipment_status_time"));
            assertTrue(hasIndex(connection, "amz_shipment", "idx_shipment_last_track"));
            assertTrue(hasIndex(connection, "amz_tracking_event", "idx_shipment_event_time_id"));
            assertTrue(hasIndex(connection, "amz_shipment", "idx_shop_master_tracking_id"));

            execute(connection, "INSERT INTO amz_shipment "
                    + "(shipment_no, shop_id, master_tracking_no, status) VALUES "
                    + "('SHP-2', 1, 'TRACK-1', 'CREATED')");
            assertEquals(2L, scalarLong(connection,
                    "SELECT COUNT(*) FROM amz_shipment WHERE master_tracking_no = 'TRACK-1'"));
        }
    }

    @Test
    @DisplayName("multiplatform V1 -> V5：明细 JSON、订单号列改名与统一订单唯一键补 shop_id")
    void multiplatformUpgradeScopesUnifiedOrderKeyToShop() throws Exception {
        String schema = resetSchema("amz_multiplatform");
        assertMigration(migrate(schema, "amz-service-multiplatform", "1"), 1);

        try (Connection connection = open(schema)) {
            execute(connection, "INSERT INTO amz_unified_order "
                    + "(unified_order_no, platform, platform_order_no, shop_id) VALUES "
                    + "('U-1', 'TEMU', 'EXT-1', 1)");
            execute(connection, "INSERT INTO amz_platform_message "
                    + "(shop_id, platform, platform_message_id, order_id, receive_time) VALUES "
                    + "(1, 'TEMU', 'MSG-1', 'EXT-ORDER-1', CURRENT_TIMESTAMP)");
        }

        assertMigration(migrate(schema, "amz-service-multiplatform", "3"), 2);

        try (Connection connection = open(schema)) {
            assertTrue(columnExists(connection, "amz_unified_order", "items_json"));
            assertTrue(columnExists(connection, "amz_platform_message", "platform_order_no"));
            assertFalse(columnExists(connection, "amz_platform_message", "order_id"));
            assertEquals("EXT-ORDER-1", scalarString(connection,
                    "SELECT platform_order_no FROM amz_platform_message WHERE platform_message_id = 'MSG-1'"));
        }

        // V4：商品/消息表唯一键补 shop_id。ShopScopedUniqueKeyContractTest 只读文件文本，
        // 这里补**运行时**的三列确认——文本里有这三列不等于 MySQL 真按三列建了键。
        assertMigration(migrate(schema, "amz-service-multiplatform", "4"), 1);
        try (Connection connection = open(schema)) {
            assertEquals(3, uniqueIndexColumnCount(connection,
                    "amz_platform_product", "uk_shop_platform_product"));
            assertEquals(3, uniqueIndexColumnCount(connection,
                    "amz_platform_message", "uk_shop_platform_msg"));
        }

        // V5 之前先固定旧键的全局唯一语义：同一 (platform, platform_order_no) 换一家店
        // 必须被 uk_platform_order 拦住。这是下面「V5 之后同一插入必须成功」的前提——
        // 若旧键本来就不拦，V5 的改动就什么都没证明。
        try (Connection connection = open(schema)) {
            execute(connection, "INSERT INTO amz_unified_order "
                    + "(unified_order_no, platform, platform_order_no, shop_id) VALUES "
                    + "('U-2', 'TEMU', 'EXT-1', 2)");
            fail("旧键 uk_platform_order 是全局唯一，B 店同一 platform_order_no 本应被拦住；"
                    + "不拦住说明这个用例测不到 V5 的效果");
        } catch (SQLException expected) {
            // 1062 Duplicate entry —— 旧键确实在拦，继续。
        }

        assertMigration(migrate(schema, "amz-service-multiplatform", "5"), 1);

        try (Connection connection = open(schema)) {
            // 新键三列且唯一；旧键已消失（只 ADD 不 DROP 时旧键仍在拦，缺陷原样回来）。
            assertEquals(3, indexColumnCount(connection, "amz_unified_order", "uk_shop_platform_order"));
            assertEquals(3, uniqueIndexColumnCount(connection, "amz_unified_order", "uk_shop_platform_order"));
            assertFalse(hasIndex(connection, "amz_unified_order", "uk_platform_order"));

            // 既有行在 DROP+ADD 后完好，且 V2/V3 的结论没被后续迁移抹掉。
            assertEquals(1L, scalarLong(connection,
                    "SELECT COUNT(*) FROM amz_unified_order WHERE unified_order_no = 'U-1' "
                            + "AND shop_id = 1 AND items_json IS NULL"));

            // V5 的真正意义：B 店同一 platform_order_no 现在能落库。
            // §S33.108 只把判重查询补上 shop_id，代码会走到 insert；若 DDL 不改，
            // 这里撞旧键报 1062，整个平台同步被标失败。
            execute(connection, "INSERT INTO amz_unified_order "
                    + "(unified_order_no, platform, platform_order_no, shop_id) VALUES "
                    + "('U-2', 'TEMU', 'EXT-1', 2)");
            assertEquals(2L, scalarLong(connection,
                    "SELECT COUNT(*) FROM amz_unified_order WHERE platform = 'TEMU' "
                            + "AND platform_order_no = 'EXT-1'"));

            // 新键仍是真的唯一：同店同平台同订单号必须被拦住。
            try {
                execute(connection, "INSERT INTO amz_unified_order "
                        + "(unified_order_no, platform, platform_order_no, shop_id) VALUES "
                        + "('U-3', 'TEMU', 'EXT-1', 1)");
                fail("新键 uk_shop_platform_order 必须拦住同店重复的 (shop_id, platform, platform_order_no)");
            } catch (SQLException expected) {
                // 1062 Duplicate entry。
            }
        }
    }

    @Test
    @DisplayName("spapi V1 -> V9：旧 512 字符凭证、旧 outbox 回填与通知表")
    void spapiUpgradeRepairsLegacySchemaAndBackfillsReplayMetadata() throws Exception {
        String schema = resetSchema("amz_spapi");
        assertMigration(migrate(schema, "amz-service-spapi", "1"), 1);

        try (Connection connection = open(schema)) {
            execute(connection, "CREATE TABLE amz_shop_credential ("
                    + "shop_id BIGINT NOT NULL, "
                    + "client_id VARCHAR(128) DEFAULT NULL, "
                    + "client_secret_encrypted VARCHAR(512) DEFAULT NULL, "
                    + "refresh_token_encrypted VARCHAR(512) DEFAULT NULL, "
                    + "access_key_encrypted VARCHAR(512) DEFAULT NULL, "
                    + "secret_key_encrypted VARCHAR(512) DEFAULT NULL, "
                    + "region VARCHAR(16) DEFAULT NULL, "
                    + "marketplace_id VARCHAR(32) DEFAULT NULL, "
                    + "seller_id VARCHAR(32) DEFAULT NULL, "
                    + "create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, "
                    + "update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP, "
                    + "PRIMARY KEY (shop_id)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 "
                    + "COLLATE=utf8mb4_unicode_ci");
            execute(connection, "INSERT INTO amz_shop_credential "
                    + "(shop_id, client_id, client_secret_encrypted, refresh_token_encrypted, "
                    + "access_key_encrypted, secret_key_encrypted, seller_id) VALUES "
                    + "(1, 'client-1', REPEAT('a', 400), REPEAT('b', 400), REPEAT('c', 400), "
                    + "REPEAT('d', 400), 'SELLER-1')");
        }

        assertMigration(migrate(schema, "amz-service-spapi", "2"), 1);

        try (Connection connection = open(schema)) {
            assertEquals(2048L, columnLength(connection, "amz_shop_credential",
                    "client_secret_encrypted"));
            assertEquals(2048L, columnLength(connection, "amz_shop_credential",
                    "refresh_token_encrypted"));
            assertEquals(64L, columnLength(connection, "amz_shop_credential", "seller_id"));
            assertEquals(400L, scalarLong(connection,
                    "SELECT LENGTH(refresh_token_encrypted) FROM amz_shop_credential WHERE shop_id = 1"));
            assertTrue(tableExists(connection, "amz_spapi_call_outbox"));

            execute(connection, "INSERT INTO amz_spapi_call_outbox "
                    + "(shop_id, operation_id, http_method, request_path, expected_status, status) VALUES "
                    + "(1, 'op-1', 'GET', '/orders/v0/orders', 202, 'PENDING')");
        }

        assertMigration(migrate(schema, "amz-service-spapi", "3"), 1);

        try (Connection connection = open(schema)) {
            assertTrue(columnExists(connection, "amz_spapi_call_outbox", "expected_statuses"));
            assertTrue(columnExists(connection, "amz_spapi_call_outbox", "rate_limit_variant"));
            assertEquals("202", scalarString(connection,
                    "SELECT expected_statuses FROM amz_spapi_call_outbox WHERE operation_id = 'op-1'"));
        }

        assertMigration(migrate(schema, "amz-service-spapi", "5"), 2);

        try (Connection connection = open(schema)) {
            assertTrue(columnExists(connection, "amz_spapi_call_outbox", "token_source"));
            assertTrue(columnExists(connection, "amz_spapi_call_outbox", "restricted_resources_encrypted"));
            assertEquals("LWA", scalarString(connection,
                    "SELECT token_source FROM amz_spapi_call_outbox WHERE operation_id = 'op-1'"));
            assertEquals(1L, scalarLong(connection,
                    "SELECT COUNT(*) FROM amz_spapi_call_outbox WHERE operation_id = 'op-1' "
                            + "AND restricted_resources_encrypted IS NULL"));
        }

        assertMigration(migrate(schema, "amz-service-spapi", "9"), 4);

        try (Connection connection = open(schema)) {
            assertTrue(columnExists(connection, "amz_shop_credential", "version"));
            assertEquals("bigint", columnDataType(connection, "amz_shop_credential", "version"));
            assertEquals("0", columnDefault(connection, "amz_shop_credential", "version"));
            assertEquals(0L, scalarLong(connection,
                    "SELECT version FROM amz_shop_credential WHERE shop_id = 1"));
            assertTrue(tableExists(connection, "amz_spapi_notification_destination"));
            assertTrue(tableExists(connection, "amz_spapi_notification_subscription"));
            assertTrue(tableExists(connection, "amz_spapi_notification_inbox"));
            assertEquals(9L, scalarLong(connection,
                    "SELECT COUNT(*) FROM flyway_schema_history WHERE success = 1"));
            assertEquals(0L, scalarLong(connection,
                    "SELECT COUNT(*) FROM flyway_schema_history WHERE success = 0"));
        }
    }

    private static MigrateResult migrate(String schema, String module, String target) {
        MigrateResult result = Flyway.configure()
                .dataSource(urlFor(schema), USER, PASSWORD)
                .locations("filesystem:" + migrationDir(module).toString().replace("\\", "/"))
                .baselineOnMigrate(true)
                .baselineVersion("1")
                .target(target)
                .cleanDisabled(true)
                .load()
                .migrate();
        assertTrue(result.success, module + " target " + target + " 迁移失败");
        return result;
    }

    private static void assertMigration(MigrateResult result, int expectedExecuted) {
        assertEquals(expectedExecuted, result.migrationsExecuted,
                "迁移执行数量与预期不一致：target=" + result.targetSchemaVersion);
    }

    private static Path migrationDir(String module) {
        Path dir = ROOT.resolve("amz-service").resolve(module)
                .resolve("src/main/resources/db/migration");
        assertTrue(Files.isDirectory(dir), "缺少迁移目录：" + dir);
        return dir;
    }

    private static String resetSchema(String logicalName) throws SQLException {
        String schema = logicalName + SCHEMA_SUFFIX;
        assertTrue(schema.endsWith(SCHEMA_SUFFIX), "只允许操作升级 IT 专用库：" + schema);
        recreateSchema(schema);
        return schema;
    }

    private static void recreateSchema(String schema) throws SQLException {
        try (Connection connection = DriverManager.getConnection(serverUrl(), USER, PASSWORD);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("DROP DATABASE IF EXISTS `" + schema + "`");
            statement.executeUpdate("CREATE DATABASE `" + schema
                    + "` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
        }
    }

    private static Connection open(String schema) throws SQLException {
        return DriverManager.getConnection(urlFor(schema), USER, PASSWORD);
    }

    private static String urlFor(String schema) {
        Matcher matcher = JDBC_BASE.matcher(BASE_URL);
        assertTrue(matcher.matches(),
                "FLYWAY_ALL_IT_URL 必须形如 jdbc:mysql://host:port/db?params：" + BASE_URL);
        return matcher.group(1) + "/" + schema + (matcher.group(2) == null ? "" : matcher.group(2));
    }

    private static String serverUrl() {
        Matcher matcher = JDBC_BASE.matcher(BASE_URL);
        assertTrue(matcher.matches(),
                "FLYWAY_ALL_IT_URL 必须形如 jdbc:mysql://host:port/db?params：" + BASE_URL);
        return matcher.group(1) + "/" + (matcher.group(2) == null ? "" : matcher.group(2));
    }

    private static boolean tableExists(Connection connection, String table) throws SQLException {
        return scalarLong(connection, "SELECT COUNT(*) FROM information_schema.tables "
                + "WHERE table_schema = DATABASE() AND table_name = '" + table + "'") > 0;
    }

    private static boolean columnExists(Connection connection, String table, String column) throws SQLException {
        return scalarLong(connection, "SELECT COUNT(*) FROM information_schema.columns "
                + "WHERE table_schema = DATABASE() AND table_name = '" + table + "' "
                + "AND column_name = '" + column + "'") > 0;
    }

    private static String columnDataType(Connection connection, String table, String column) throws SQLException {
        return columnString(connection, table, column, "DATA_TYPE");
    }

    private static Long columnLength(Connection connection, String table, String column) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(
                     "SELECT CHARACTER_MAXIMUM_LENGTH FROM information_schema.columns "
                             + "WHERE table_schema = DATABASE() AND table_name = '" + table + "' "
                             + "AND column_name = '" + column + "'")) {
            assertTrue(resultSet.next(), "列不存在：" + table + "." + column);
            long value = resultSet.getLong(1);
            return resultSet.wasNull() ? null : value;
        }
    }

    private static String columnDefault(Connection connection, String table, String column) throws SQLException {
        return columnString(connection, table, column, "COLUMN_DEFAULT");
    }

    private static String columnComment(Connection connection, String table, String column) throws SQLException {
        return columnString(connection, table, column, "COLUMN_COMMENT");
    }

    private static String columnString(Connection connection, String table, String column, String field)
            throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(
                     "SELECT " + field + " FROM information_schema.columns "
                             + "WHERE table_schema = DATABASE() AND table_name = '" + table + "' "
                             + "AND column_name = '" + column + "'")) {
            assertTrue(resultSet.next(), "列不存在：" + table + "." + column);
            return resultSet.getString(1);
        }
    }

    private static int indexColumnCount(Connection connection, String table, String index) throws SQLException {
        return scalarInt(connection, "SELECT COUNT(*) FROM information_schema.statistics "
                + "WHERE table_schema = DATABASE() AND table_name = '" + table + "' "
                + "AND index_name = '" + index + "'");
    }

    private static int uniqueIndexColumnCount(Connection connection, String table, String index)
            throws SQLException {
        return scalarInt(connection, "SELECT COUNT(*) FROM information_schema.statistics "
                + "WHERE table_schema = DATABASE() AND table_name = '" + table + "' "
                + "AND index_name = '" + index + "' AND non_unique = 0");
    }

    private static boolean hasIndex(Connection connection, String table, String index) throws SQLException {
        return indexColumnCount(connection, table, index) > 0;
    }

    private static long scalarLong(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(sql)) {
            assertTrue(resultSet.next(), "查询应返回一行：" + sql);
            return resultSet.getLong(1);
        }
    }

    private static int scalarInt(Connection connection, String sql) throws SQLException {
        return Math.toIntExact(scalarLong(connection, sql));
    }

    private static String scalarString(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(sql)) {
            assertTrue(resultSet.next(), "查询应返回一行：" + sql);
            return resultSet.getString(1);
        }
    }

    private static void execute(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate(sql);
        }
    }

    private static Path findRepoRoot() {
        Path current = Path.of("").toAbsolutePath();
        for (int depth = 0; current != null && depth < 8; depth++, current = current.getParent()) {
            if (Files.isRegularFile(current.resolve(".github/workflows/ci.yml"))
                    && Files.isDirectory(current.resolve("amz-service"))) {
                return current;
            }
        }
        throw new IllegalStateException("无法从 user.dir 定位仓库根目录：" + System.getProperty("user.dir"));
    }
}
