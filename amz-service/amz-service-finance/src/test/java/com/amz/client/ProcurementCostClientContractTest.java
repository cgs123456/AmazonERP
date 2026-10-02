package com.amz.client;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

@DisplayName("采购成本 Feign 路径契约")
class ProcurementCostClientContractTest {

    @Test
    @DisplayName("批次成本调用路径包含采购服务 /procurement 前缀")
    void costSummaryPathMatchesProcurementController() throws Exception {
        // 前缀必须来自 @FeignClient(path = ...)：类级 @RequestMapping 会让上下文刷新直接失败
        // （"@RequestMapping annotation not allowed on @FeignClient interfaces"，2026-10-02 实测）。
        // 这条断言换写法时不能跟着松——前缀丢了不会报错，只会让每次跨服务调用 404 并被降级吞成空数据。
        assertNull(ProcurementCostClient.class.getAnnotation(RequestMapping.class),
                "ProcurementCostClient 不得带类级 @RequestMapping，服务会起不来");

        FeignClient client = ProcurementCostClient.class.getAnnotation(FeignClient.class);
        assertNotNull(client, "ProcurementCostClient 必须是 @FeignClient");
        assertEquals("/procurement", client.path(),
                "路由前缀必须由 @FeignClient(path = ...) 声明，否则调用路径与采购服务控制器对不上");

        Method method = ProcurementCostClient.class.getMethod(
                "getCostSummary", Long.class, String.class);
        GetMapping methodMapping = method.getAnnotation(GetMapping.class);
        assertNotNull(methodMapping, "批次成本接口必须是 GET 端点");

        String actualPath = client.path() + methodMapping.value()[0];
        assertEquals("/procurement/batch/cost-summary/{shopId}", actualPath);
    }
}