package com.amz.mapper;

import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdDailyReportMapperContractTest {

    @Test
    @DisplayName("活动级报表必须由数据库按 campaign 聚合，并以 MIN(id) 做稳定 keyset 游标")
    void campaignAggregationPushesGroupingAndKeysetIntoDatabase() throws Exception {
        Method method = AdDailyReportMapper.class.getMethod(
                "aggregateCampaignReports", Long.class, LocalDate.class, LocalDate.class, Long.class, int.class);
        Select select = method.getAnnotation(Select.class);

        assertNotNull(select, "活动级报表必须使用显式聚合 SQL，禁止 selectList 全量回内存");
        String sql = String.join(" ", select.value()).replaceAll("\s+", " ").toUpperCase();
        assertTrue(sql.contains("FROM AMZ_AD_DAILY_REPORT"), sql);
        assertTrue(sql.contains("GROUP BY CAMPAIGN_ID"), "必须按活动在数据库内聚合：" + sql);
        assertTrue(sql.contains("MIN(ID)"), "活动游标必须使用稳定的 MIN(id)：" + sql);
        assertTrue(sql.contains("HAVING MIN(ID) &GT; #{CURSORID}"), "必须支持 MIN(id) keyset 游标：" + sql);
        assertTrue(sql.contains("LIMIT #{LIMIT}"), "必须显式限制探测行数：" + sql);
        assertTrue(sql.contains("SHOP_ID = #{SHOPID}"), "必须限定店铺：" + sql);
    }

    @Test
    @DisplayName("店铺汇总必须由数据库一次聚合，禁止先加载全部活动")
    void shopSummaryUsesSingleDatabaseAggregation() throws Exception {
        Method method = AdDailyReportMapper.class.getMethod(
                "aggregateShopReport", Long.class, LocalDate.class, LocalDate.class);
        Select select = method.getAnnotation(Select.class);

        assertNotNull(select, "店铺汇总必须使用显式聚合 SQL");
        String sql = String.join(" ", select.value()).replaceAll("\s+", " ").toUpperCase();
        assertTrue(sql.contains("SUM(IMPRESSIONS)"), sql);
        assertTrue(sql.contains("SUM(COST)"), sql);
        assertTrue(sql.contains("SUM(SALES)"), sql);
        assertTrue(sql.contains("SHOP_ID = #{SHOPID}"), sql);
    }
}
