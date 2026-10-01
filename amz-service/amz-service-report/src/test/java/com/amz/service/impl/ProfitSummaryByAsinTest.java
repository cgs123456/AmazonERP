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
 * 排序、利润率计算、订单数口径与失败口径 —— 不重新发明一遍 SQL。
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
        when(mapper.countDistinctOrders(SHOP_ID, null, null)).thenReturn(4L);

        Map<String, Object> result = service.profitSummaryByAsin(SHOP_ID, null, null);

        assertEquals(4L, result.get("totalOrders"), "订单数＝全店去重订单数");
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
        assertEquals(3L, summaries.get(1).get("orderCount"), "组内是去重订单数（A1 三个订单）");
    }

    /**
     * 这一条是口径变更的全部理由：一单可以跨多个 ASIN。
     * 实测 fixture（7 行 / 4 个订单）在 MySQL 8.4 上给出 B1=3、B2=2，相加得 <b>5</b>，
     * 而全店 {@code COUNT(DISTINCT amazon_order_id)} 是 <b>4</b>；
     * 旧的 {@code COUNT(1)} 行数口径则是 <b>7</b>。
     * 所以总数必须另查，不能把分组列加起来。
     */
    @Test
    @DisplayName("totalOrders 走全店去重，不能把各 ASIN 的去重数相加")
    void totalOrdersIsShopWideDistinctNotSumOfAsinCounts() {
        when(mapper.sumByAsin(eq(SHOP_ID), isNull(), isNull())).thenReturn(List.of(
                group("B1", 3, "40.00", "5.00", "1.00", "5.75", "5.00", "3.50"),
                group("B2", 2, "47.00", "5.00", "2.00", "9.00", "7.00", "6.00")));
        when(mapper.countDistinctOrders(SHOP_ID, null, null)).thenReturn(4L);

        Map<String, Object> result = service.profitSummaryByAsin(SHOP_ID, null, null);

        assertEquals(4L, result.get("totalOrders"),
                "3+2=5 会把「一单跨两 ASIN」数两次；真实订单只有 4 个");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> summaries = (List<Map<String, Object>>) result.get("asinSummaries");
        assertEquals(3L + 2L, ((Number) summaries.get(0).get("orderCount")).longValue()
                + ((Number) summaries.get(1).get("orderCount")).longValue(),
                "分组列仍按 ASIN 各自去重，两者本就不该相等");
    }

    @Test
    @DisplayName("总数查询与分组查询必须用同一组日期边界，否则明细与总数对不上")
    void countQueryUsesTheSameDateWindowAsTheGroupQuery() {
        LocalDate from = LocalDate.of(2026, 9, 1);
        LocalDate to = LocalDate.of(2026, 9, 30);
        when(mapper.sumByAsin(eq(SHOP_ID), eq(from), eq(to)))
                .thenReturn(List.of(group("A1", 2, "15.00", "3.00", "1.00", "4.00", "2.00", "1.00")));
        when(mapper.countDistinctOrders(SHOP_ID, from, to)).thenReturn(2L);

        Map<String, Object> result = service.profitSummaryByAsin(SHOP_ID, "2026-09-01", "2026-09-30");

        assertEquals(2L, result.get("totalOrders"));
        verify(mapper).countDistinctOrders(SHOP_ID, from, to);
        verify(mapper, never()).countDistinctOrders(eq(SHOP_ID), isNull(), isNull());
    }

    @Test
    @DisplayName("销售额为 0 的组利润率取 0，不做除法")
    void zeroSalesGroupHasZeroMargin() {
        when(mapper.sumByAsin(eq(SHOP_ID), isNull(), isNull())).thenReturn(List.of(
                group("A-Z", 1, "0.00", "0.00", "0.00", "0.00", "0.00", "0.00")));
        when(mapper.countDistinctOrders(SHOP_ID, null, null)).thenReturn(1L);

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
        when(mapper.countDistinctOrders(any(), any(), any())).thenReturn(3L);

        service.profitSummaryByAsin(SHOP_ID, "2026-09-01", "2026-09-30");

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
        when(mapper.countDistinctOrders(SHOP_ID, null, null)).thenReturn(1L);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.profitSummaryByAsin(SHOP_ID, null, null));
        assertTrue(error.getMessage().contains("grossProfit"), error.getMessage());
    }

    @Test
    @DisplayName("总数查询返回 null（空表等）时按 0 输出，不抛给调用方")
    void nullFromCountBecomesZero() {
        when(mapper.sumByAsin(eq(SHOP_ID), isNull(), isNull())).thenReturn(List.of());
        when(mapper.countDistinctOrders(SHOP_ID, null, null)).thenReturn(null);

        Map<String, Object> result = service.profitSummaryByAsin(SHOP_ID, null, null);

        assertEquals(0L, result.get("totalOrders"));
    }

    /** MySQL 实际回传类型：COUNT(DISTINCT) 是 Long，SUM(DECIMAL) 是 DECIMAL，分组键是 String。 */
    private static Map<String, Object> group(String asin, long orderCount, String totalSales,
                                             String totalCost, String totalAdSpend, String totalFees,
                                             String grossProfit, String netProfit) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("asin", asin);
        row.put("orderCount", orderCount);
        row.put("totalSales", new BigDecimal(totalSales));
        row.put("totalCost", new BigDecimal(totalCost));
        row.put("totalAdSpend", new BigDecimal(totalAdSpend));
        row.put("totalFees", new BigDecimal(totalFees));
        row.put("grossProfit", new BigDecimal(grossProfit));
        row.put("netProfit", new BigDecimal(netProfit));
        return row;
    }
}
