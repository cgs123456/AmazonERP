package com.amz.migration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("广告派生表唯一键迁移契约")
class AdUniquenessMigrationContractTest {

    @Test
    @DisplayName("V7 先规范化并去重，再建立三张表的业务唯一键")
    void v7DeduplicatesBeforeAddingUniqueKeys() throws Exception {
        Path migration = Path.of("src/main/resources/db/migration/V7__ad_business_uniqueness.sql");
        assertTrue(Files.exists(migration), "缺少广告业务唯一键迁移 V7");

        String sql = Files.readString(migration).toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
        int keywordNormalize = sql.indexOf("update amz_ad_keyword");
        int keywordDelete = sql.indexOf("delete older from amz_ad_keyword");
        int keywordUnique = sql.indexOf("uk_ad_keyword_shop_campaign_keyword_match");
        int convertingNormalize = sql.indexOf("update amz_ad_converting_terms");
        int convertingDelete = sql.indexOf("delete older from amz_ad_converting_terms");
        int convertingUnique = sql.indexOf("uk_ad_converting_shop_campaign_term");
        int asinNormalize = sql.indexOf("update amz_ad_asin_keyword");
        int asinDelete = sql.indexOf("delete older from amz_ad_asin_keyword");
        int asinUnique = sql.indexOf("uk_ad_asin_shop_asin_keyword");

        assertTrue(keywordNormalize >= 0 && keywordNormalize < keywordDelete && keywordDelete < keywordUnique,
                "amz_ad_keyword 必须按 规范化 -> 去重 -> 唯一键 执行");
        assertTrue(convertingNormalize >= 0 && convertingNormalize < convertingDelete && convertingDelete < convertingUnique,
                "amz_ad_converting_terms 必须按 规范化 -> 去重 -> 唯一键 执行");
        assertTrue(asinNormalize >= 0 && asinNormalize < asinDelete && asinDelete < asinUnique,
                "amz_ad_asin_keyword 必须按 规范化 -> 去重 -> 唯一键 执行");
    }

    @Test
    @DisplayName("V7 唯一键覆盖真实业务维度并保留空活动可迁移")
    void v7UsesProductionBusinessDimensions() throws Exception {
        Path migration = Path.of("src/main/resources/db/migration/V7__ad_business_uniqueness.sql");
        String sql = Files.readString(migration).toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");

        assertTrue(sql.contains("(shop_id, campaign_id, keyword, match_type)"),
                "关键词唯一键必须包含 shop/campaign/keyword/match_type");
        assertTrue(sql.contains("(shop_id, campaign_id, search_term)"),
                "出单词唯一键必须包含 shop/campaign/search_term");
        assertTrue(sql.contains("(shop_id, asin, keyword)"),
                "ASIN 反查唯一键必须包含 shop/asin/keyword");
        assertTrue(sql.contains("set campaign_id = '' where campaign_id is null"),
                "历史空活动必须先回填为空串，避免 NULL 绕过唯一键");
        assertTrue(sql.contains("modify campaign_id varchar(50) not null default ''"),
                "出单词活动列必须收敛为 NOT NULL DEFAULT ''");
    }
}