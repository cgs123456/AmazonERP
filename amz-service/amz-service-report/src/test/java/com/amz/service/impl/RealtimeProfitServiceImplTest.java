package com.amz.service.impl;

import com.amz.mapper.CostAllocationMapper;
import com.amz.mapper.ProfitDetailMapper;
import com.amz.mapper.ProfitSnapshotMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;

/**
 * 实时利润服务单元测试（纯 Mockito，不依赖数据库）。
 * <p>
 * 回归 B2：profitSummary 改走 SQL GROUP BY 后，响应结构（sku/asin/sales/netProfit/
 * margin/snapshotCount、netProfit 降序、 totals/overallMargin）必须与逐行版一致。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("实时利润服务单元测试")
class RealtimeProfitServiceImplTest {

    @Mock
    private ProfitSnapshotMapper profitSnapshotMapper;

    @Mock
    private ProfitDetailMapper profitDetailMapper;

    @Mock
    private CostAllocationMapper costAllocationMapper;

    @Mock
    private ObjectMapper objectMapper;

    @InjectMocks
    private RealtimeProfitServiceImpl profitService;

    @Test
    @DisplayName("profitSummary：聚合分组正确求和并按 netProfit 降序")
    void profitSummaryAggregatesGroups() {
        when(profitSnapshotMapper.sumBySku(eq(1L), isNull(), isNull())).thenReturn(Arrays.asList(
                group("A", "ASIN-A", new BigDecimal("1000"), new BigDecimal("200"), 2L),
                group("B", "ASIN-B", new BigDecimal("500"), new BigDecimal("50"), 1L)));

        Map<String, Object> result = profitService.profitSummary(1L, null, null);

        assertEquals(new BigDecimal("1500"), result.get("totalSales"));
        assertEquals(new BigDecimal("250"), result.get("totalNetProfit"));
        assertEquals(new BigDecimal("16.67"), result.get("overallMargin"));
        assertEquals(2, result.get("skuCount"));
        List<?> summaries = (List<?>) result.get("skuSummaries");
        assertEquals(2, summaries.size());
        Map<?, ?> first = (Map<?, ?>) summaries.get(0);
        assertEquals("A", first.get("sku"));
        assertEquals("ASIN-A", first.get("asin"));
        assertEquals(new BigDecimal("20.00"), first.get("margin"));
    }

    @Test
    @DisplayName("profitSummary：空分组返回零值")
    void profitSummaryEmpty() {
        when(profitSnapshotMapper.sumBySku(eq(1L), any(), any()))
                .thenReturn(List.of());

        Map<String, Object> result = profitService.profitSummary(1L, "2026-01-01 00:00:00",
                "2026-01-31 23:59:59");

        assertEquals(BigDecimal.ZERO, result.get("totalSales"));
        assertEquals(0, result.get("skuCount"));
    }

    private static Map<String, Object> group(String sku, String asin, BigDecimal sales,
                                             BigDecimal netProfit, Long snapshotCount) {
        Map<String, Object> g = new LinkedHashMap<>();
        g.put("sku", sku);
        g.put("asin", asin);
        g.put("sales", sales);
        g.put("netProfit", netProfit);
        g.put("snapshotCount", snapshotCount);
        return g;
    }
}
