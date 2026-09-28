package com.amz.client;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@DisplayName("采购成本 Feign 路径契约")
class ProcurementCostClientContractTest {

    @Test
    @DisplayName("批次成本调用路径包含采购服务 /procurement 前缀")
    void costSummaryPathMatchesProcurementController() throws Exception {
        RequestMapping classMapping = ProcurementCostClient.class.getAnnotation(RequestMapping.class);
        assertNotNull(classMapping, "ProcurementCostClient 必须声明 /procurement 路由前缀");

        Method method = ProcurementCostClient.class.getMethod(
                "getCostSummary", Long.class, String.class);
        GetMapping methodMapping = method.getAnnotation(GetMapping.class);
        assertNotNull(methodMapping, "批次成本接口必须是 GET 端点");

        String actualPath = classMapping.value()[0] + methodMapping.value()[0];
        assertEquals("/procurement/batch/cost-summary/{shopId}", actualPath);
    }
}