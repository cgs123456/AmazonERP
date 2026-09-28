package com.amz.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("搜索词派生表原子 upsert 契约")
class AdSearchTermUpsertMapperContractTest {

    @Test
    @DisplayName("出单词按店铺活动搜索词唯一键原子覆盖统计快照")
    void convertingTermUpsertUsesBusinessUniqueKeySemantics() throws Exception {
        Method method = ConvertingTermMapper.class.getMethod("upsertBatch", List.class);
        Insert insert = method.getAnnotation(Insert.class);

        assertNotNull(insert, "出单词批量写入必须使用显式 SQL");
        String sql = normalize(insert.value());
        assertTrue(sql.contains("INSERT INTO AMZ_AD_CONVERTING_TERMS"), sql);
        assertTrue(sql.contains("ON DUPLICATE KEY UPDATE"), sql);
        assertTrue(sql.contains("TOTAL_ORDERS = VALUES(TOTAL_ORDERS)"), sql);
        assertTrue(sql.contains("STATUS") && !sql.contains("STATUS = VALUES(STATUS)"),
                "覆盖统计不得重置人工维护的 ACTIVE/ARCHIVED 状态");
        assertTrue(sql.contains("IS_ADDED_TO_KEYWORD") && !sql.contains("IS_ADDED_TO_KEYWORD = VALUES"),
                "覆盖统计不得重置人工维护的已加词标记");
    }

    @Test
    @DisplayName("出单词回读必须同时限定店铺、活动和搜索词")
    void convertingTermReadbackIsTenantAndCampaignScoped() throws Exception {
        Method method = ConvertingTermMapper.class.getMethod(
                "selectByKeys", Long.class, List.class, List.class);
        Select select = method.getAnnotation(Select.class);

        assertNotNull(select, "出单词回读必须使用显式 SQL");
        String sql = normalize(select.value());
        assertTrue(sql.contains("SHOP_ID = #{SHOPID}"), sql);
        assertTrue(sql.contains("CAMPAIGN_ID IN"), sql);
        assertTrue(sql.contains("SEARCH_TERM IN"), sql);
    }

    @Test
    @DisplayName("ASIN 关键词按店铺 ASIN 关键词唯一键原子 upsert")
    void asinKeywordUpsertUsesBusinessUniqueKeySemantics() throws Exception {
        Method method = AdAsinKeywordMapper.class.getMethod("upsertBatch", List.class);
        Insert insert = method.getAnnotation(Insert.class);

        assertNotNull(insert, "ASIN 关键词批量写入必须使用显式 SQL");
        String sql = normalize(insert.value());
        assertTrue(sql.contains("INSERT INTO AMZ_AD_ASIN_KEYWORD"), sql);
        assertTrue(sql.contains("ON DUPLICATE KEY UPDATE"), sql);
        assertTrue(sql.contains("ORGANIC_RANK = VALUES(ORGANIC_RANK)"), sql);
        assertTrue(sql.contains("SEARCH_VOLUME = VALUES(SEARCH_VOLUME)"), sql);
    }

    @Test
    @DisplayName("ASIN 关键词回读必须限定店铺 ASIN 和关键词")
    void asinKeywordReadbackIsTenantScoped() throws Exception {
        Method method = AdAsinKeywordMapper.class.getMethod(
                "selectByKeys", Long.class, List.class, List.class);
        Select select = method.getAnnotation(Select.class);

        assertNotNull(select, "ASIN 关键词回读必须使用显式 SQL");
        String sql = normalize(select.value());
        assertTrue(sql.contains("SHOP_ID = #{SHOPID}"), sql);
        assertTrue(sql.contains("ASIN IN"), sql);
        assertTrue(sql.contains("KEYWORD IN"), sql);
    }

    private static String normalize(String[] sql) {
        return String.join(" ", sql).replaceAll("\\s+", " ").toUpperCase(Locale.ROOT);
    }
}