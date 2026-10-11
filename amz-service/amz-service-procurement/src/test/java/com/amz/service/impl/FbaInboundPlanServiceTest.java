package com.amz.service.impl;

import com.amz.client.SpApiFbaInboundClient;
import com.amz.exception.CodeErrorException;
import com.amz.result.Result;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * FBA 入库计划契约（补货建议 → Amazon 真实入库计划）。
 * <p>
 * <b>存在动因</b>：{@code fbaInbound} 的 45 条 operation 早已进统一目录与限流表，
 * 但采购侧业务层对它们零调用——补货建议落不成真实入库计划。本测试钉住官方请求形状
 * （依据 {@code contracts/fulfillmentInbound_2024-03-20.json}）与「失败不伪造计划」。
 * <p>
 * 变异验证（写本测试时实际注入确认会红）：把 items 里的 prepOwner 去掉 → 形状断言红；
 * 把「spapi 失败即抛错」改成返回空 Map → 第三例红。
 */
@DisplayName("FBA 入库计划：官方请求形状与不伪造")
class FbaInboundPlanServiceTest {

    private final SpApiFbaInboundClient client = mock(SpApiFbaInboundClient.class);
    private final FbaInboundPlanService service = newService(client);

    @Test
    @DisplayName("请求形状：destinationMarketplaces(1) + items(含必填四字段) + sourceAddress")
    void buildsOfficialShape() {
        when(client.execute(anyString(), any())).thenReturn(Result.success(Map.of("inboundPlanId", "PLAN-1")));

        Map<String, Object> result = service.createInboundPlan(
                9001L, "ATVPDKIKX0DER", "补货计划A",
                address(),
                List.of(new FbaInboundPlanService.InboundLine("SKU-1", 30, "SELLER", "SELLER")));

        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<Map<String, Object>> captor =
                org.mockito.ArgumentCaptor.forClass(Map.class);
        verify(client).execute(eq("fbaInbound.createInboundPlan"), captor.capture());

        Map<String, Object> request = captor.getValue();
        assertEquals(9001L, request.get("shopId"));
        assertEquals("ATVPDKIKX0DER", request.get("marketplaceId"));

        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) request.get("body");
        assertEquals(List.of("ATVPDKIKX0DER"), body.get("destinationMarketplaces"),
                "官方要求恰好一个目的站点");
        assertEquals("补货计划A", body.get("name"));
        assertTrue(body.containsKey("sourceAddress"), "官方必填 sourceAddress");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) body.get("items");
        assertEquals(1, items.size());
        Map<String, Object> item = items.get(0);
        assertEquals("SKU-1", item.get("msku"));
        assertEquals(30, item.get("quantity"));
        assertEquals("SELLER", item.get("prepOwner"), "官方 ItemInput 必填 prepOwner");
        assertEquals("SELLER", item.get("labelOwner"), "官方 ItemInput 必填 labelOwner");
        assertEquals("PLAN-1", result.get("inboundPlanId"));
    }

    @Test
    @DisplayName("必填校验：缺地址/缺明细/非法 owner 一律本地拒绝")
    void validatesInputs() {
        assertThrows(CodeErrorException.class, () -> service.createInboundPlan(
                9001L, "ATVPDKIKX0DER", null, null,
                List.of(new FbaInboundPlanService.InboundLine("SKU-1", 1, "SELLER", "SELLER"))),
                "地址必填且无合理默认值");

        assertThrows(CodeErrorException.class, () -> service.createInboundPlan(
                9001L, "ATVPDKIKX0DER", null, address(), List.of()),
                "至少一行明细");

        assertThrows(CodeErrorException.class, () -> service.createInboundPlan(
                9001L, "ATVPDKIKX0DER", null, address(),
                List.of(new FbaInboundPlanService.InboundLine("SKU-1", 1, "MYSELF", "SELLER"))),
                "owner 只支持 SELLER/AMAZON");

        assertThrows(CodeErrorException.class, () -> service.createInboundPlan(
                9001L, "ATVPDKIKX0DER", null, address(),
                List.of(new FbaInboundPlanService.InboundLine("SKU-1", 0, "SELLER", "SELLER"))),
                "数量必须为正");

        verify(client, never()).execute(anyString(), any());
    }

    @Test
    @DisplayName("spapi 失败：明确抛错，绝不返回伪造的 inboundPlanId")
    void doesNotFabricatePlanOnFailure() {
        when(client.execute(anyString(), any())).thenReturn(Result.failure("spapi degraded"));

        CodeErrorException e = assertThrows(CodeErrorException.class, () -> service.createInboundPlan(
                9001L, "ATVPDKIKX0DER", null, address(),
                List.of(new FbaInboundPlanService.InboundLine("SKU-1", 5, "SELLER", "SELLER"))));
        assertTrue(e.getMessage().contains("创建 FBA 入库计划失败"),
                "失败必须点名，实际：" + e.getMessage());
    }

    @Test
    @DisplayName("地址 countryCode 必须是两位")
    void validatesCountryCode() {
        assertThrows(CodeErrorException.class, () -> service.createInboundPlan(
                9001L, "ATVPDKIKX0DER", null,
                new FbaInboundPlanService.SourceAddress("仓", "街 1", null, "深圳", null, null, "CHN", "518000"),
                List.of(new FbaInboundPlanService.InboundLine("SKU-1", 1, "SELLER", "SELLER"))));
        verify(client, never()).execute(anyString(), any());
    }

    // ============ 辅助 ============

    private FbaInboundPlanService newService(SpApiFbaInboundClient client) {
        FbaInboundPlanService s = new FbaInboundPlanService();
        ReflectionTestUtils.setField(s, "spApiFbaInboundClient", client);
        return s;
    }

    private FbaInboundPlanService.SourceAddress address() {
        return new FbaInboundPlanService.SourceAddress("深圳仓", "科技园路 1 号", null,
                "深圳", null, "GD", "CN", "518000");
    }
}
