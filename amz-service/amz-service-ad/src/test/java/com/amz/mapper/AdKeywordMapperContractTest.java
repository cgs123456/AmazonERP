package com.amz.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdKeywordMapperContractTest {

    @Test
    @DisplayName("基准价批量插入不得覆盖已认领基准价")
    void insertBaseBidsNeverOverwritesClaimedValue() throws Exception {
        Method method = AdKeywordMapper.class.getMethod("insertBaseBidRows", java.util.List.class);
        Insert insert = method.getAnnotation(Insert.class);

        assertNotNull(insert, "基准价批量插入必须使用显式 SQL");
        String sql = String.join(" ", insert.value()).replaceAll("\s+", " ").toUpperCase();
        assertTrue(sql.contains("INSERT INTO AMZ_AD_KEYWORD"), sql);
        assertTrue(sql.contains("BASE_BID = COALESCE(BASE_BID, VALUES(BASE_BID))"), sql);
    }

    @Test
    @DisplayName("已有行补基准价必须只更新空值")
    void updateBaseBidsOnlyFillsNullValues() throws Exception {
        Method method = AdKeywordMapper.class.getMethod("updateBaseBidsByIds", Long.class, java.util.List.class);
        Update update = method.getAnnotation(Update.class);

        assertNotNull(update, "基准价批量更新必须使用显式 SQL");
        String sql = String.join(" ", update.value()).replaceAll("\s+", " ").toUpperCase();
        assertTrue(sql.contains("SET BASE_BID = CASE ID"), sql);
        assertTrue(sql.contains("BASE_BID IS NULL"), sql);
        assertTrue(sql.contains("SHOP_ID = #{SHOPID}"), sql);
    }
}