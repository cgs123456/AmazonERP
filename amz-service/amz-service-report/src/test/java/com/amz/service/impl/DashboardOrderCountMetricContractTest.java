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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * 仪表盘"订单数"口径测试。
 * <p>
 * 存在动因：本方法此前调 {@code /order/profit/summary/{shopId}} 并取**返回行数**当订单数，
 * 而该接口是 {@code GROUP BY shop_id, sku, 月份} 的利润汇总（无订单数量列），
 * 行数既不是订单数、也不随 dateRange 变化；客单价 = 销售额 / 该行数 因此同样失真，
 * 且分子分母窗口不一致。现改为取 {@code /order/list} 的 {@code data.total}
 * （同窗口订单表分页总数）。
 * <p>
 * 用 Mockito 而非起服务：这里要钉的是"取哪个字段的口径"，
 * 编解码路径已由 {@code OrderServiceFeignDecodeIT} 与 {@code ResultJsonDecodeContractTest} 覆盖。
 */
@DisplayName("仪表盘订单数：必须取同窗口订单总数，不是利润汇总行数")
@ExtendWith(MockitoExtension.class)
class DashboardOrderCountMetricContractTest {

    @Mock
    private OrderServiceFeignClient orderFeignClient;

    @Mock
    private AdServiceFeignClient adFeignClient;

    @Mock
    private FinanceServiceFeignClient financeFeignClient;

    @InjectMocks
    private RealReportServiceImpl reportService;

    @Test
    @DisplayName("订单数取 data.total，客单价用同一窗口相除")
    void readsOrderTotalFromOrderList() {
        Map<String, Object> orderData = new LinkedHashMap<>();
        orderData.put("total", 42L);
        orderData.put("orders", List.of());
        // 只有 days=30（dateRange="30d"）才匹配：窗口对不上就取不到数，测试随即变红
        when(orderFeignClient.listOrders(7L, 30)).thenReturn(envelope(orderData));
        when(financeFeignClient.calculateProfit(eq(7L), any(), any()))
                .thenReturn(envelope(new BigDecimal("1000.00")));

        DashboardReport report = reportService.getDashboard(7L, "30d");

        assertEquals(42, report.getTotalOrders(),
                "订单数必须来自 /order/list 的 data.total；取到 0 说明窗口或字段名又漂了");
        assertEquals(new BigDecimal("23.81"), report.getAvgOrderValue(),
                "客单价 = 1000.00 / 42 = 23.81，分子分母必须同窗口");
    }

    @Test
    @DisplayName("三种拿不到数的形状都按 0 处理，不许抛错也不许蒙一个数")
    void degradationAndContractDriftAllYieldZero() {
        // (a) fallback 工厂的 Collections.emptyMap()：连信封都没有
        when(orderFeignClient.listOrders(7L, 7)).thenReturn(Map.of());
        // (b) 对端业务失败（Result.failure 的 code=400，data 为 null）
        Map<String, Object> failure = new LinkedHashMap<>();
        failure.put("code", 400);
        failure.put("message", "shopId must not be null");
        failure.put("data", null);
        when(orderFeignClient.listOrders(7L, 30)).thenReturn(failure);
        // (c) 信封正常但 total 是字符串——旧实现会 parseInt 出一个假订单数
        Map<String, Object> drifted = new LinkedHashMap<>();
        drifted.put("total", "42");
        when(orderFeignClient.listOrders(7L, 90)).thenReturn(envelope(drifted));
        when(financeFeignClient.calculateProfit(eq(7L), any(), any()))
                .thenReturn(envelope(new BigDecimal("100.00")));

        assertEquals(0, reportService.getDashboard(7L, null).getTotalOrders(),
                "空响应体（fallback 兜底）必须按 0，且留 WARN 说明是无响应体而非无数据");
        assertEquals(0, reportService.getDashboard(7L, "30d").getTotalOrders(),
                "对端非 200 不能当成这个窗口 0 单");
        assertEquals(0, reportService.getDashboard(7L, "90d").getTotalOrders(),
                "total 不是数字属契约漂移，按 0 处理，不得用 toString 蒙一个数");

        assertNull(reportService.getDashboard(7L, "90d").getAvgOrderValue(),
                "订单数为 0 时不算客单价（除零），保持 null 让前端显示为空而不是假的 0.00");
    }

    private static Map<String, Object> envelope(Object data) {
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("code", 200);
        resp.put("message", "操作成功");
        resp.put("data", data);
        return resp;
    }
}
