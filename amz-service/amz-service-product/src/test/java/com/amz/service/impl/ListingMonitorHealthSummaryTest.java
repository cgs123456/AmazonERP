package com.amz.service.impl;

import com.amz.mapper.ListingHealthMapper;
import com.amz.model.ListingHealth;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code healthSummary} 的换算与可见性（聚合已下沉 SQL，见 mapper 注释与静态契约）。
 * <p>
 * mock 必须<b>按参数应答</b>：{@code selectWorstListings} 的条数是行为的一部分，
 * 无条件的 thenReturn 会让"传错 limit"永远测不出来。
 */
@DisplayName("Listing 健康度汇总：口径换算、缺列即报、行序只取最差 N 条")
class ListingMonitorHealthSummaryTest {

    private final ListingHealthMapper healthMapper = mock(ListingHealthMapper.class);
    private final ListingMonitorServiceImpl service = service();

    private ListingMonitorServiceImpl service() {
        ListingMonitorServiceImpl impl = new ListingMonitorServiceImpl();
        ReflectionTestUtils.setField(impl, "listingHealthMapper", healthMapper);
        return impl;
    }

    @Test
    @DisplayName("整店口径数字由聚合行换算而来，且不再整店读入明细")
    void summaryComesFromAggregationNotFromAFullScan() {
        when(healthMapper.aggregateHealthSummary(7L)).thenReturn(
                aggregate(600, 420, 120, 60, new BigDecimal("45000"), 600));
        when(healthMapper.selectWorstListings(eq(7L), anyInt()))
                .thenReturn(List.of(worst("A1", 12, "CRITICAL", "图片不合规")));

        Map<String, Object> result = service.healthSummary(7L);

        assertEquals(600L, ((Number) result.get("total")).longValue());
        assertEquals(420L, ((Number) result.get("ok")).longValue());
        assertEquals(120L, ((Number) result.get("warning")).longValue());
        assertEquals(60L, ((Number) result.get("critical")).longValue());
        // 45000 / 600 = 75.0，四舍五入到一位小数
        assertEquals(75.0, ((Number) result.get("avgScore")).doubleValue(), 0.0001);
        // 420 / 600 = 70.0%
        assertEquals(70.0, ((Number) result.get("healthRate")).doubleValue(), 0.0001);

        verify(healthMapper, never()).selectList(any());
        verify(healthMapper).selectWorstListings(7L, 5);
    }

    @Test
    @DisplayName("最差清单：条数按参数取，键与原实现一致，null 不再炸掉整个接口")
    void worstListingsMapShapeIsPreservedAndNullTolerant() {
        when(healthMapper.aggregateHealthSummary(7L)).thenReturn(aggregate(3, 0, 1, 2, null, 2));
        // 第二行故意把 health_score / severity / suppressed_reason 置空：
        // 原实现用 Map.of 组装，任一列为 NULL 就 NPE，整个汇总接口 500。
        Map<String, Object> blank = new HashMap<>();
        blank.put("asin", "A2");
        when(healthMapper.selectWorstListings(eq(7L), eq(5))).thenReturn(List.of(
                worst("A1", 30, "CRITICAL", null), blank));

        Map<String, Object> result = service.healthSummary(7L);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> worst = (List<Map<String, Object>>) result.get("worstListings");
        assertEquals(2, worst.size());
        assertEquals(List.of("asin", "score", "severity", "reason"), List.copyOf(worst.get(0).keySet()),
                "返回结构变了，前端取值会静默拿不到字段");
        assertEquals("", worst.get(0).get("reason"), "缺原因沿用旧的\"空串\"表示");
        assertNull(worst.get(1).get("score"), "分数缺失就报缺失，不要拿 0 冒充已检查过");
        assertNull(worst.get(1).get("severity"));
        // scoreCount=2（NULL 行不进分母），scoreSum=NULL → 平均按 0 处理
        assertEquals(0.0, ((Number) result.get("avgScore")).doubleValue(), 0.0001);
        assertEquals(0, ((Number) result.get("healthRate")).intValue(), "没有 OK 行时健康率为 0");
    }

    @Test
    @DisplayName("聚合结果缺列＝查询被改坏，必须抛错而不是按 0 汇总")
    void missingAggregateColumnFailsLoud() {
        Map<String, Object> partial = aggregate(10, 5, 3, 2, new BigDecimal("500"), 10);
        partial.remove("okCount");
        when(healthMapper.aggregateHealthSummary(7L)).thenReturn(partial);

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> service.healthSummary(7L));
        assertTrue(ex.getMessage().contains("okCount"),
                "报错要指名缺哪一列，否则排查只能猜：" + ex.getMessage());
    }

    @Test
    @DisplayName("空店：total=0 时不给除零，也不谎报健康率")
    void emptyStoreReportsZerosNotAnError() {
        when(healthMapper.aggregateHealthSummary(7L)).thenReturn(aggregate(0, 0, 0, 0, null, 0));
        when(healthMapper.selectWorstListings(eq(7L), anyInt())).thenReturn(List.of());

        Map<String, Object> result = service.healthSummary(7L);

        assertEquals(0L, ((Number) result.get("total")).longValue());
        assertEquals(0.0, ((Number) result.get("avgScore")).doubleValue(), 0.0001);
        assertEquals(0, ((Number) result.get("healthRate")).intValue());
        assertTrue(((List<?>) result.get("worstListings")).isEmpty());
    }

    private static Map<String, Object> aggregate(long total, long ok, long warning, long critical,
                                                 BigDecimal scoreSum, long scoreCount) {
        Map<String, Object> row = new HashMap<>();
        row.put("total", total);
        row.put("okCount", ok);
        row.put("warningCount", warning);
        row.put("criticalCount", critical);
        row.put("scoreSum", scoreSum);
        row.put("scoreCount", scoreCount);
        return row;
    }

    private static Map<String, Object> worst(String asin, Integer score, String severity, String reason) {
        Map<String, Object> row = new HashMap<>();
        row.put("asin", asin);
        row.put("healthScore", score);
        row.put("severity", severity);
        row.put("suppressedReason", reason);
        return row;
    }

    /** 占位：确保 ListingHealth 类型在本用例可见（编译期防漂移，mapper 返回类型变了会暴露）。 */
    @SuppressWarnings("unused")
    private static final Class<?> ENTITY = ListingHealth.class;
}
