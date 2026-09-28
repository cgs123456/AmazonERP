package com.amz.migration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("广告素材与定向租户迁移契约")
class AdResourceTenantMigrationContractTest {

    private static final String CREATIVE = "amz_ad_creative";
    private static final String TARGETING = "amz_ad_targeting";

    @Test
    @DisplayName("V5 为素材和定向增加可空 shop_id，避免猜测歧义历史数据归属")
    void v5AddsNullableShopScopeToBothResources() throws Exception {
        String sql = migrationSql();

        assertNullableShopId(sql, CREATIVE);
        assertNullableShopId(sql, TARGETING);
        assertFalse(sql.contains("add column shop_id bigint not null"),
                "V5 不得在历史歧义数据未清零时强制 NOT NULL");
    }

    @Test
    @DisplayName("V5 只回填 campaign_id 唯一映射一个店铺的记录")
    void v5OnlyBackfillsUnambiguousCampaignOwnership() throws Exception {
        String sql = migrationSql();

        assertTrue(sql.contains("having count(distinct shop_id) = 1"),
                "两个回填语句都必须用 COUNT(DISTINCT shop_id)=1 排除多店复用 campaign_id");
        assertTrue(sql.contains("set c.shop_id = resolved.shop_id"),
                "素材表必须按唯一店铺映射回填");
        assertTrue(sql.contains("set t.shop_id = resolved.shop_id"),
                "定向表必须按唯一店铺映射回填");
        assertTrue(sql.contains("where c.shop_id is null"), "素材回填不得覆盖已确认归属");
        assertTrue(sql.contains("where t.shop_id is null"), "定向回填不得覆盖已确认归属");
    }

    @Test
    @DisplayName("V5 为租户过滤路径建立素材和定向复合索引")
    void v5AddsTenantAwareCompositeIndexes() throws Exception {
        String sql = migrationSql();

        assertTrue(sql.contains("idx_ad_creative_shop_campaign")
                        && sql.contains("(shop_id, campaign_id, id)"),
                "素材查询需要 (shop_id, campaign_id, id) 索引");
        assertTrue(sql.contains("idx_ad_targeting_shop_campaign")
                        && sql.contains("(shop_id, campaign_id, targeting_type, id)"),
                "定向查询需要 (shop_id, campaign_id, targeting_type, id) 索引");
    }

    private static String migrationSql() throws Exception {
        Path migration = Path.of("src/main/resources/db/migration/V5__ad_resource_tenant_scope.sql");
        assertTrue(Files.exists(migration), "缺少广告资源租户迁移 V5");
        return Files.readString(migration)
                .toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", " ");
    }

    private static void assertNullableShopId(String sql, String table) {
        assertTrue(sql.contains("alter table " + table + " add column shop_id bigint null"),
                table + " 必须增加可空 shop_id");
    }
}