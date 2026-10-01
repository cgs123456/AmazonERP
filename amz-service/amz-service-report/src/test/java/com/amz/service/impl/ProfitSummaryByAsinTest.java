package com.amz.service.impl;

import com.amz.mapper.ProfitDetailMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 利润 ASIN 聚合报表的服务层行为。
 * <p>
 * 分组数字取自 MySQL 8.4 对同一份 fixture 的真实聚合结果（含 NULL 分项、
 * 同订单同 ASIN 重复行、跨年份行、跨店铺行），这里只验证服务在聚合之上的
 * 排序、利润率计算与失败口径 —— 不重新发明一遍 SQL。
 */
@DisplayName("利润 ASIN 聚合报表")
class ProfitSummaryByAsinTest {

    private static final Long SHOP_ID = 1L;

    private ProfitDetailMapper mapper;
    private ReportUpgradeServiceImpl service;

    @BeforeEach
    void setUp() {
        mapper = mock(ProfitDetailMapper.class);
        service = new ReportUpgradeServiceImpl();
        ReflectionTestUtils.setField(service, "profitDetailMapper", mapper);
    }

    @Test
    @DisplayName("按净利润降序，利润率按组内净额/销售额计算，合计跨组相加")
    void aggregatesRollUpAndSortByNetProfit() {
        when(mapper.sumByAsin(eq(SHOP_ID), isNull(), isNull())).thenReturn(List.of(
                group("A1", 3, "22.50", "5.00", "1.00", "5.75", "5.00", "3.50"),
                group("A2", 1, "20.00", "5.00", "2.00", "9.00", "7.00", "6.00")));

        Map<String, Object> result = service.profitSummaryByAsin(SHOP_ID, null, null);

        assertEquals(4L, result.get("totalOrders"), "totalOrders 沿用历史口径=明细行数");
        assertEquals(0, new BigDecimal("42.50").compareTo((BigDecimal) result.get("totalSales")));
        assertEquals(0, new BigDecimal("10.00").compareTo((BigDecimal) result.get("totalCost")));
        assertEquals(0, new BigDecimal("9.50").compareTo((BigDecimal) result.get("totalNetProfit")));
        assertEquals(0, new BigDecimal("22.35").compareTo((BigDecimal) result.get("overallMargin")));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> summaries = (List<Map<String, Object>>) result.get("asinSummaries");
        assertEquals(2, summaries.size());
        assertEquals("A2", summaries.get(0).get("asin"), "应按净利润降序：6.00 > 3.50");
        assertEquals(0, new BigDecimal("30.00").compareTo((BigDecimal) summaries.get(0).get("margin")));
        assertEquals(0, new BigDecimal("15.56").compareTo((BigDecimal) summaries.get(1).get("margin")),
                "3.50/22.50*100 四舍五入两位 = 15.56");
        assertEquals(3L, summaries.get(1).get("orderCount"), "沿用历史口径=组内行数");
    }

    @Test
    @DisplayName("销售额为 0 的组利润率取 0，不做除法")
    void zeroSalesGroupHasZeroMargin() {
        when(mapper.sumByAsin(eq(SHOP_ID), isNull(), isNull())).thenReturn(List.of(
                group("A-Z", 1, "0.00", "0.00", "0.00", "0.00", "0.00", "0.00")));

        Map<String, Object> result = service.profitSummaryByAsin(SHOP_ID, null, null);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> summaries = (List<Map<String, Object>>) result.get("asinSummaries");
        assertEquals(0, BigDecimal.ZERO.compareTo((BigDecimal) summaries.get(0).get("margin")));
        assertEquals(0, BigDecimal.ZERO.compareTo((BigDecimal) result.get("overallMargin")));
    }

    @Test
    @DisplayName("日期字符串解析后交给 SQL 层过滤，服务不再读取明细行")
    void dateBoundsArePassedDown() {
        when(mapper.sumByAsin(eq(SHOP_ID), eq(LocalDate.of(2026, 9, 1)), eq(LocalDate.of(2026, 9, 30))))
                .thenReturn(List.of(
                        group("A1", 2, "15.00", "3.00", "1.00", "4.00", "2.00", "1.00"),
                        group("A2", 1, "20.00", "5.00", "2.00", "9.00", "7.00", "6.00")));

        Map<String, Object> result = service.profitSummaryByAsin(
                SHOP_ID, "2026-09-01", "2026-09-30");

        assertEquals(3L, result.get("totalOrders"), "总单数就是各 ASIN 聚合行的合计（2 + 1）");
        verify(mapper, never()).selectList(any());
    }

    @Test
    @DisplayName("非法日期字符串仍然抛错，不静默当成无边界全表聚合")
    void malformedDateStillFails() {
        assertThrows(DateTimeException.class,
                () -> service.profitSummaryByAsin(SHOP_ID, "2026-13-45", null));
    }

    @Test
    @DisplayName("聚合结果缺列时抛错，不把缺失当 0 静默通过")
    void missingColumnFailsLoud() {
        Map<String, Object> broken = new HashMap<>(group("A1", 1, "1.00", "1.00", "0.00", "0.00",
                "0.00", "0.00"));
        broken.remove("grossProfit");
        when(mapper.sumByAsin(eq(SHOP_ID), isNull(), isNull())).thenReturn(List.of(broken));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.profitSummaryByAsin(SHOP_ID, null, null));
        assertTrue(error.getMessage().contains("grossProfit"), error.getMessage());
    }

    /** MySQL 实际回传类型：COUNT 是 Long，SUM(DECIMAL) 是 DECIMAL，分组键是 String。 */
    private static Map<String, Object> group(String asin, long rowCount, String totalSales,
                                             String totalCost, String totalAdSpend, String totalFees,
                                             String grossProfit, String netProfit) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("asin", asin);
        row.put("rowCount", rowCount);
        row.put("totalSales", new BigDecimal(totalSales));
        row.put("totalCost", new BigDecimal(totalCost));
        row.put("totalAdSpend", new BigDecimal(totalAdSpend));
        row.put("totalFees", new BigDecimal(totalFees));
        row.put("grossProfit", new BigDecimal(grossProfit));
        row.put("netProfit", new BigDecimal(netProfit));
        return row;
    }
}
