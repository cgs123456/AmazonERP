package com.amz.service.impl;

import com.amz.client.SpApiFbaInboundClient;
import com.amz.exception.CodeErrorException;
import com.amz.result.Result;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
 * FBA 入库计划后续步骤（读选项 + 回写跟踪号）。
 * <p>
 * <b>为什么这些测试重要</b>：这些方法都是「把平台状态读出来交给运营决策」，
 * 一旦把失败读成空结果，运营会以为「Amazon 还没生成装箱选项」或「分仓没有费用」，
 * 而不是「这次调用失败了」。因此判据集中在：
 * <ul>
 *   <li>失败必须抛错，不能返回空 Map；</li>
 *   <li>路径参数名与官方一致（{@code inboundPlanId}/{@code shipmentId}），
 *       写错名字会得到 404 而不是编译错误；</li>
 *   <li>跟踪号必须是箱级映射，单个主运单号不得被当成逐箱数据；</li>
 *   <li>所有方法只调 catalog 里的 FBA Inbound operation，不会误落到别的家族。</li>
 * </ul>
 */
@DisplayName("FBA 入库计划后续步骤：读取失败必须响亮、跟踪号必须箱级")
class FbaInboundPlanQueryServiceTest {

    private static final Long SHOP_ID = 9001L;
    private static final String MARKETPLACE = "ATVPDKIKX0DER";
    private static final String PLAN_ID = "wf1234abcd-1234-abcd-5678-1234abcd5678";
    private static final String SHIPMENT_ID = "sh1234abcd-1234-abcd-5678-1234abcd5678";

    private final SpApiFbaInboundClient client = mock(SpApiFbaInboundClient.class);
    private final FbaInboundPlanQueryService service = newService(client);

    @Test
    @DisplayName("读取计划：operation 与路径参数名必须与官方一致")
    void getPlanUsesOfficialOperationAndPathParameter() {
        when(client.execute(anyString(), any())).thenReturn(Result.success(Map.of("status", "ACTIVE")));

        Map<String, Object> result = service.getInboundPlan(SHOP_ID, MARKETPLACE, PLAN_ID);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(client).execute(eq("fbaInbound.getInboundPlan"), captor.capture());
        Map<String, Object> request = captor.getValue();
        assertEquals(SHOP_ID, request.get("shopId"));
        assertEquals(MARKETPLACE, request.get("marketplaceId"));
        @SuppressWarnings("unchecked")
        Map<String, String> path = (Map<String, String>) request.get("pathParameters");
        assertEquals(PLAN_ID, path.get("inboundPlanId"), "官方路径参数名是 inboundPlanId");
        assertEquals("ACTIVE", result.get("status"));
    }

    @Test
    @DisplayName("装箱/分仓选项：各自落到对应 operation，且只传 inboundPlanId")
    void optionListsUseTheirOwnOperations() {
        when(client.execute(anyString(), any())).thenReturn(Result.success(Map.of()));

        service.listPackingOptions(SHOP_ID, MARKETPLACE, PLAN_ID);
        service.listPlacementOptions(SHOP_ID, MARKETPLACE, PLAN_ID);

        verify(client).execute(eq("fbaInbound.listPackingOptions"), any());
        verify(client).execute(eq("fbaInbound.listPlacementOptions"), any());
    }

    @Test
    @DisplayName("货件详情/明细：必须同时带 inboundPlanId 与 shipmentId")
    void shipmentReadsCarryBothPathParameters() {
        when(client.execute(anyString(), any())).thenReturn(Result.success(Map.of()));

        service.getShipment(SHOP_ID, MARKETPLACE, PLAN_ID, SHIPMENT_ID);
        service.listShipmentItems(SHOP_ID, MARKETPLACE, PLAN_ID, SHIPMENT_ID);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(client).execute(eq("fbaInbound.getShipment"), captor.capture());
        @SuppressWarnings("unchecked")
        Map<String, String> path = (Map<String, String>) captor.getValue().get("pathParameters");
        assertEquals(PLAN_ID, path.get("inboundPlanId"));
        assertEquals(SHIPMENT_ID, path.get("shipmentId"));
        verify(client).execute(eq("fbaInbound.listShipmentItems"), any());
    }

    @Test
    @DisplayName("读取失败：抛错而不是返回空 Map（空 Map 会被读成「没有选项」）")
    void readFailureIsLoud() {
        when(client.execute(anyString(), any())).thenReturn(Result.failure("spapi degraded"));

        CodeErrorException e = assertThrows(CodeErrorException.class,
                () -> service.listPlacementOptions(SHOP_ID, MARKETPLACE, PLAN_ID));
        assertTrue(e.getMessage().contains("fbaInbound.listPlacementOptions"),
                "失败信息必须点名 operation：" + e.getMessage());
    }

    @Test
    @DisplayName("客户端未装配：抛明确的不可用错误，不发请求")
    void missingClientFailsClosed() {
        FbaInboundPlanQueryService without = new FbaInboundPlanQueryService();

        CodeErrorException e = assertThrows(CodeErrorException.class,
                () -> without.getInboundPlan(SHOP_ID, MARKETPLACE, PLAN_ID));
        assertTrue(e.getMessage().contains("未装配"), e.getMessage());
        verify(client, never()).execute(anyString(), any());
    }

    @Test
    @DisplayName("跟踪号回写：请求体是箱级 SPD 结构，不是单个主运单号")
    void trackingBodyIsBoxLevelSpd() {
        when(client.execute(anyString(), any())).thenReturn(Result.success(Map.of("operationId", "op-1")));

        Map<String, String> boxes = new LinkedHashMap<>();
        boxes.put("FBA10ABC0YY100001", "1Z999AA10123456784");
        boxes.put("FBA10ABC0YY100002", "1Z999AA10123456785");
        service.updateTracking(SHOP_ID, MARKETPLACE, PLAN_ID, SHIPMENT_ID, boxes);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(client).execute(eq("fbaInbound.updateShipmentTrackingDetails"), captor.capture());

        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) captor.getValue().get("body");
        @SuppressWarnings("unchecked")
        Map<String, Object> trackingDetails = (Map<String, Object>) body.get("trackingDetails");
        @SuppressWarnings("unchecked")
        Map<String, Object> spd = (Map<String, Object>) trackingDetails.get("spdTrackingDetail");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) spd.get("spdTrackingItems");
        assertEquals(2, items.size());
        assertEquals("FBA10ABC0YY100001", items.get(0).get("boxId"));
        assertEquals("1Z999AA10123456784", items.get(0).get("trackingId"));
        // 不得出现「主运单号」这种整单字段：官方 SPD 结构没有它
        assertFalse(body.containsKey("trackingNumber"), "不得伪造整单跟踪号字段");
    }

    @Test
    @DisplayName("跟踪号回写：空映射/空箱号/空跟踪号一律本地拒绝")
    void trackingRejectsIncompleteInput() {
        assertThrows(CodeErrorException.class,
                () -> service.updateTracking(SHOP_ID, MARKETPLACE, PLAN_ID, SHIPMENT_ID, Map.of()),
                "空映射等于什么都没回写");
        assertThrows(CodeErrorException.class,
                () -> service.updateTracking(SHOP_ID, MARKETPLACE, PLAN_ID, SHIPMENT_ID,
                        Map.of("  ", "1Z999")),
                "空 boxId");
        assertThrows(CodeErrorException.class,
                () -> service.updateTracking(SHOP_ID, MARKETPLACE, PLAN_ID, SHIPMENT_ID,
                        Map.of("BOX-1", "  ")),
                "空跟踪号");
        verify(client, never()).execute(anyString(), any());
    }

    @Test
    @DisplayName("异步状态查询：落到 getInboundOperationStatus，路径参数名是 operationId")
    void operationStatusUsesOfficialOperation() {
        when(client.execute(anyString(), any())).thenReturn(Result.success(Map.of("operationStatus", "SUCCESS")));

        Map<String, Object> result = service.getOperationStatus(SHOP_ID, MARKETPLACE, "op-1");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(client).execute(eq("fbaInbound.getInboundOperationStatus"), captor.capture());
        @SuppressWarnings("unchecked")
        Map<String, String> path = (Map<String, String>) captor.getValue().get("pathParameters");
        assertEquals("op-1", path.get("operationId"));
        assertEquals("SUCCESS", result.get("operationStatus"));
    }

    @Test
    @DisplayName("必填校验：shopId/marketplaceId/计划 ID 任一为空都本地拒绝")
    void requiresScopeAndIds() {
        assertThrows(CodeErrorException.class,
                () -> service.getInboundPlan(null, MARKETPLACE, PLAN_ID));
        assertThrows(CodeErrorException.class,
                () -> service.getInboundPlan(SHOP_ID, " ", PLAN_ID));
        assertThrows(CodeErrorException.class,
                () -> service.getInboundPlan(SHOP_ID, MARKETPLACE, " "));
        verify(client, never()).execute(anyString(), any());
    }

    private static FbaInboundPlanQueryService newService(SpApiFbaInboundClient client) {
        FbaInboundPlanQueryService s = new FbaInboundPlanQueryService();
        ReflectionTestUtils.setField(s, "spApiFbaInboundClient", client);
        return s;
    }
}
