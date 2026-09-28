package com.amz.migration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("广告活动元数据数据库迁移契约")
class AdCampaignMigrationContractTest {

    @Test
    @DisplayName("V2 清理重复活动并为元数据 upsert 建立店铺活动唯一键")
    void v2DeduplicatesCampaignMetadataAndAddsUniqueKey() throws Exception {
        Path migration = Path.of("src/main/resources/db/migration/V2__campaign_metadata_unique.sql");
        assertTrue(Files.exists(migration), "缺少活动元数据唯一键迁移 V2");

        String sql = Files.readString(migration).toLowerCase(Locale.ROOT).replaceAll("\s+", " ");
        assertTrue(sql.contains("delete"), "迁移必须先清理历史重复数据");
        assertTrue(sql.contains("shop_id") && sql.contains("campaign_id"),
                "去重必须按 shop_id + campaign_id");
        assertTrue(sql.contains("unique key uk_shop_campaign"),
                "迁移必须建立 (shop_id, campaign_id) 唯一键");
    }
}
