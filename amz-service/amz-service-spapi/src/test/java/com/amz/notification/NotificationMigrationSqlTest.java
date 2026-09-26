package com.amz.notification;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 通知入站三张表的 DDL 契约测试。
 * <p>
 * 目的：把「去重键」「密文字段」「租约索引」这类一旦被改掉就会造成静默丢事件的结构，
 * 用测试钉住。没有真实 SQS 可联调时，这类结构断言是唯一能拿到的离线证据。
 */
class NotificationMigrationSqlTest {

    private static String read(String resource) {
        try (InputStream in = NotificationMigrationSqlTest.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new AssertionError("迁移脚本不存在: " + resource);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void destinationTableHasShopScopedUniqueKey() {
        String sql = read("/db/migration/V6__spapi_notification_destination.sql");
        assertTrue(sql.contains("amz_spapi_notification_destination"));
        assertTrue(sql.contains("uk_destination_shop_market"));
        assertTrue(sql.contains("`shop_id`"), "destination 必须能反查店铺，否则通知无法归属");
        assertTrue(sql.contains("synthetic"), "必须有合成数据标记，防止演练数据混入生产");
    }

    @Test
    void subscriptionTableIsResolvableBySubscriptionId() {
        String sql = read("/db/migration/V7__spapi_notification_subscription.sql");
        assertTrue(sql.contains("amz_spapi_notification_subscription"));
        assertTrue(sql.contains("uk_subscription_id"));
        assertTrue(sql.contains("idx_subscription_type"));
        assertTrue(sql.contains("`shop_id`"));
        assertTrue(sql.contains("`marketplace_id`"));
    }

    @Test
    void inboxTableHasDedupKeyAndIndexes() {
        String sql = read("/db/migration/V8__spapi_notification_inbox.sql");
        assertTrue(sql.contains("amz_spapi_notification_inbox"));
        assertTrue(sql.contains("uk_notification_id"), "入口去重必须靠 notification_id 唯一键");
        assertTrue(sql.contains("idx_inbox_due"), "缺少 (status, next_attempt_at) 索引会让重试扫描全表");
        assertTrue(sql.contains("idx_inbox_shop_type_time"));
        assertTrue(sql.contains("idx_inbox_lease"), "租约回收需要 (lease_owner, lease_until) 索引");
        assertTrue(sql.contains("duplicate_count"));
        assertTrue(sql.contains("lease_until"));
    }

    @Test
    void inboxPayloadIsNeverPlaintext() {
        String sql = read("/db/migration/V8__spapi_notification_inbox.sql");
        assertTrue(sql.contains("payload_encrypted"), "载荷必须是密文字段");
        assertFalse(sql.contains("`payload` TEXT"), "禁止存在明文 payload 列");
        assertFalse(sql.contains("`payload` MEDIUMTEXT"), "禁止存在明文 payload 列");
        assertTrue(sql.contains("payload_sha256"), "需要哈希来发现同 id 不同内容的重投");
    }

    @Test
    void inboxDefaultStatusIsReceived() {
        String sql = read("/db/migration/V8__spapi_notification_inbox.sql");
        assertTrue(sql.contains("DEFAULT 'RECEIVED'"), "默认状态必须是 RECEIVED，等待 Worker 领取");
    }
}