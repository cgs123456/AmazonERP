package com.amz.controller;

import com.amz.result.Result;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 促销方案端点不得返回编造的方案。
 *
 * <p><b>为什么要这条测试</b>：该端点此前无论传什么 shopId / asin / goal，
 * 都返回同一套写死的常量（Lightning Deal / 20% / "150%" / "$500 广告预算"）。
 * 前端因此**故意不接**它，但 Agent 工具 {@code generate_promotion_plan}
 * 一直在调——LLM 会把模板当经营建议讲给运营。
 *
 * <p>断言刻意盯「不能出现像结论的东西」，而不只是「返回 400」：
 * 只要响应里还带着 {@code discountRate=0.20} 这类字段，调用方就可能把它当方案用。
 */
@DisplayName("促销方案：没有数据源时不得编造")
class PromotionPlanNoFabricationTest {

    private final ProcurementController controller = new ProcurementController();

    @Test
    @DisplayName("任何店铺都返回失败，且不携带任何方案字段")
    void alwaysFailsWithoutPlanPayload() {
        Result<Map<String, Object>> res = controller.getPromotionPlan(1L, "B0TEST", "清库存");

        assertEquals(400, res.getCode());
        assertNull(res.getData(), "失败响应不得携带方案数据");
        String message = res.getMessage();
        assertNotNullMessage(message);

        // 不能残留模板里的任何一项
        assertFalse(message.contains("Lightning Deal"), message);
        assertFalse(message.contains("150%"), message);
        assertFalse(message.contains("$500"), message);
        assertFalse(message.contains("0.2"), message);
        // 必须说清楚「缺什么」，否则调用方只能转达一句「失败」
        assertTrue(message.contains("尚未接入"), message);
        assertTrue(message.contains("历史销量"), message);
    }

    @Test
    @DisplayName("不同入参得到同一条失败信息——不再因参数不同而「生成」不同方案")
    void differentInputsProduceSameHonestFailure() {
        Result<Map<String, Object>> a = controller.getPromotionPlan(1L, "B0AAA", "提升销量");
        Result<Map<String, Object>> b = controller.getPromotionPlan(2L, "B0BBB", "清库存");

        assertEquals(a.getCode(), b.getCode());
        assertEquals(a.getMessage(), b.getMessage());
        assertNull(a.getData());
        assertNull(b.getData());
    }

    @Test
    @DisplayName("shopId 为空仍是参数错误，与「能力未接入」区分开")
    void nullShopIdIsAParameterError() {
        Result<Map<String, Object>> res = controller.getPromotionPlan(null, null, null);

        assertEquals(400, res.getCode());
        assertTrue(res.getMessage().contains("shopId"), res.getMessage());
    }

    private static void assertNotNullMessage(String message) {
        assertTrue(message != null && !message.isBlank(), "失败必须带原因");
    }
}
