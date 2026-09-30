package com.amz.service.impl;

import com.amz.client.feign.AdServiceFeignClient;
import com.amz.client.feign.FinanceServiceFeignClient;
import com.amz.client.feign.OrderServiceFeignClient;
import com.amz.dto.DashboardReport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * 降级必须随响应可见：兜底出来的 0 不能被当成"这个周期没有销售"。
 * <p>
 * <b>实测发现的缺陷（2026-09-30 代码审查 High）</b>：report 的三个 Feign 客户端都带
 * fallbackFactory，且熔断装配是开着的，于是下游不可用时<b>不抛异常</b>、
 * 返回 {@code Collections.emptyMap()} —— 调用方的 catch 根本不触发，
 * extractData 拿到 null，指标落成 0。看板与经营决策看到的是一个看似正常的零值。
 * 单靠日志区分还不够：响应消费方（前端、导出、下游统计）读不到日志。
 * <p>
 * 现在响应里带 {@code degradedSources}：空数组才代表"每个来源都真实取到过数据"。
 * 同时必须避免矫枉过正 —— {@code code=200, data=null} 是合法业务空值，不计降级，
 * 否则每个零销售周期都会被误报成下游故障。
 */
@DisplayName("仪表盘降级可见性：兜底零值必须标注来源，合法空值不得误报")
@ExtendWith(MockitoExtension.class)
class DashboardDegradationVisibilityTest {

    @Mock
    private OrderServiceFeignClient orderFeignClient;
    @Mock
    private AdServiceFeignClient adFeignClient;
    @Mock
    private FinanceServiceFeignClient financeFeignClient;

    @InjectMocks
    private RealReportServiceImpl reportService;

    @Test
    @DisplayName("三个下游都在兜底：degradedSources 必须点名 finance/order/ad")
    void fallbackEnvelopesAreReported() {
        // 这正是 fallbackFactory 的实际返回值
        when(financeFeignClient.calculateProfit(eq(7L), any(), any())).thenReturn(Map.of());
        when(orderFeignClient.listOrders(7L, 30)).thenReturn(Map.of());
        when(adFeignClient.getShopSummary(7L)).thenReturn(Map.of());

        DashboardReport report = reportService.getDashboard(7L, "30d");

        assertEquals(List.of("ad", "finance", "order"), sortedCopy(report.getDegradedSources()),
                "零值来自兜底时必须让调用方看出来；实际：" + report.getDegradedSources());
        assertEquals(0, report.getTotalSales().compareTo(BigDecimal.ZERO));
        assertEquals(0, report.getTotalOrders());
    }

    @Test
    @DisplayName("全部真实取到数：degradedSources 为空")
    void healthySourcesReportNothing() {
        when(financeFeignClient.calculateProfit(eq(7L), any(), any()))
                .thenReturn(envelope(new BigDecimal("1000.00")));
        Map<String, Object> orderData = new LinkedHashMap<>();
        orderData.put("total", 42L);
        orderData.put("orders", List.of());
        when(orderFeignClient.listOrders(7L, 30)).thenReturn(envelope(orderData));
        when(adFeignClient.getShopSummary(7L)).thenReturn(envelope(Map.of("spend", 5.0)));

        DashboardReport report = reportService.getDashboard(7L, "30d");

        assertEquals(List.of(), report.getDegradedSources(),
                "所有来源都正常时不应出现降级标记；实际：" + report.getDegradedSources());
        assertEquals(42, report.getTotalOrders());
        assertEquals(new BigDecimal("23.81"), report.getAvgOrderValue());
    }

    @Test
    @DisplayName("code=200 且 data=null 是合法业务空值：不得误报成下游故障")
    void legitimateEmptyResultIsNotDegradation() {
        when(financeFeignClient.calculateProfit(eq(7L), any(), any())).thenReturn(envelope(null));
        Map<String, Object> orderData = new LinkedHashMap<>();
        orderData.put("total", 0L);
        orderData.put("orders", List.of());
        when(orderFeignClient.listOrders(7L, 30)).thenReturn(envelope(orderData));
        when(adFeignClient.getShopSummary(7L)).thenReturn(envelope(Map.of()));

        DashboardReport report = reportService.getDashboard(7L, "30d");

        assertEquals(List.of(), report.getDegradedSources(),
                "该周期真的没有销售 ≠ 下游不可用；误报会让运维追不存在的问题");
        assertEquals(0, report.getTotalSales().compareTo(BigDecimal.ZERO));
    }

    @Test
    @DisplayName("对端返回非 200 业务码：只有出错的那个来源被点名")
    void nonSuccessCodeNamesOnlyThatSource() {
        Map<String, Object> failure = new LinkedHashMap<>();
        failure.put("code", 400);
        failure.put("message", "shopId must not be null");
        failure.put("data", null);
        when(financeFeignClient.calculateProfit(eq(7L), any(), any())).thenReturn(failure);
        Map<String, Object> orderData = new LinkedHashMap<>();
        orderData.put("total", 7L);
        orderData.put("orders", List.of());
        when(orderFeignClient.listOrders(7L, 30)).thenReturn(envelope(orderData));
        when(adFeignClient.getShopSummary(7L)).thenReturn(envelope(Map.of("spend", 1.0)));

        DashboardReport report = reportService.getDashboard(7L, "30d");

        assertEquals(List.of("finance"), report.getDegradedSources(),
                "降级来源要精确到具体依赖，不能整片标红也不能漏标");
        assertTrue(report.getTotalOrders() == 7, "其它来源正常时应照常取到数");
    }

    // ------------------------------------------------------------------ helpers

    private static Map<String, Object> envelope(Object data) {
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("code", 200);
        resp.put("message", "操作成功");
        resp.put("data", data);
        return resp;
    }

    /** 集合断言用固定顺序，避免 HashMap 顺序导致偶发失败。 */
    private static List<String> sortedCopy(List<String> values) {
        return values.stream().sorted().toList();
    }
}
