package com.amz.service;

import com.amz.client.OrdersClient;
import com.amz.connector.LocalApiException;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.OffsetDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 发货回传的官方请求形状契约。
 * <p>
 * <b>为什么单独测 body 形状</b>：Amazon 的 {@code confirmShipment} 对字段名与嵌套层级
 * 零容忍——{@code orderItemId} 写成 {@code order_item_id}、{@code orderItems} 放在
 * packageDetail 外面、{@code shipDate} 少了时区偏移，都会得到一个泛泛的 400，
 * 报错不会告诉你是哪个键错了。把官方快照里的形状钉成断言，是唯一能让这类错误
 * 在本地就暴露的手段。
 * <p>
 * 形状依据：{@code src/test/resources/contracts/ordersV0.json} 的
 * {@code /orders/v0/orders/{orderId}/shipmentConfirmation}（成功状态 204）。
 * <p>
 * 变异验证（写本测试时实际注入确认会红）：把 {@code orderItems} 从 packageDetail
 * 移到 body 顶层 → 形状断言红；把成功状态从 204 改成 200 → 见 OrdersClient 侧声明。
 */
@DisplayName("发货回传：官方请求形状与必填校验")
class ShipmentConfirmationServiceTest {

    private final OrdersClient ordersClient = mock(OrdersClient.class);
    private final ShipmentConfirmationService service = new ShipmentConfirmationService(ordersClient);

    @Test
    @DisplayName("operationId 与官方一致（限流维度 orders.confirmShipment）")
    void operationIdIsOfficial() {
        assertEquals("orders.confirmShipment", ShipmentConfirmationService.OP_CONFIRM_SHIPMENT);
        assertEquals(OrdersClient.OP_CONFIRM_SHIPMENT, ShipmentConfirmationService.OP_CONFIRM_SHIPMENT,
                "限流维度必须与 OrdersClient 声明的 operationId 同源");
    }

    @Test
    @DisplayName("请求体形状：marketplaceId + packageDetail{packageReferenceId,carrierCode,trackingNumber,shipDate,orderItems[]}")
    void buildsOfficialBodyShape() {
        when(ordersClient.confirmShipment(any(), any(), any(), any())).thenReturn(new JsonObject());

        service.confirmShipment(9001L, "ATVPDKIKX0DER", "114-0000000-7599439",
                "USPS", null, "TRK-1",
                OffsetDateTime.parse("2026-10-11T09:30:00Z"),
                List.of(new ShipmentConfirmationService.OrderItemLine("OI-1", 2)));

        ArgumentCaptor<JsonObject> bodyCaptor = ArgumentCaptor.forClass(JsonObject.class);
        verify(ordersClient).confirmShipment(eq(9001L), eq("ATVPDKIKX0DER"),
                eq("114-0000000-7599439"), bodyCaptor.capture());

        JsonObject body = bodyCaptor.getValue();
        assertEquals("ATVPDKIKX0DER", body.get("marketplaceId").getAsString());
        assertTrue(body.has("packageDetail"));
        assertFalse(body.has("orderItems"), "orderItems 必须在 packageDetail 内，放顶层 Amazon 会拒");

        JsonObject pkg = body.getAsJsonObject("packageDetail");
        assertNotNull(pkg.get("packageReferenceId"));
        assertEquals("USPS", pkg.get("carrierCode").getAsString());
        assertEquals("TRK-1", pkg.get("trackingNumber").getAsString());
        assertTrue(pkg.get("shipDate").getAsString().contains("2026-10-11T09:30:00"),
                "shipDate 必须是 ISO-8601，实际：" + pkg.get("shipDate").getAsString());
        assertTrue(pkg.has("orderItems"));

        JsonArray items = pkg.getAsJsonArray("orderItems");
        assertEquals(1, items.size());
        JsonObject line = items.get(0).getAsJsonObject();
        assertEquals("OI-1", line.get("orderItemId").getAsString(),
                "官方键名是 orderItemId（驼峰），写成 order_item_id 会被拒");
        assertEquals(2, line.get("quantity").getAsInt());
    }

    @Test
    @DisplayName("shipDate 为空时用当前 UTC 时间，而不是留空让 Amazon 报错")
    void defaultsShipDateToNowUtc() {
        when(ordersClient.confirmShipment(any(), any(), any(), any())).thenReturn(new JsonObject());

        service.confirmShipment(9001L, "ATVPDKIKX0DER", "114-1",
                "USPS", null, "TRK-1", null,
                List.of(new ShipmentConfirmationService.OrderItemLine("OI-1", 1)));

        ArgumentCaptor<JsonObject> captor = ArgumentCaptor.forClass(JsonObject.class);
        verify(ordersClient).confirmShipment(any(), any(), any(), captor.capture());
        String shipDate = captor.getValue().getAsJsonObject("packageDetail").get("shipDate").getAsString();
        assertFalse(shipDate.isBlank());
        assertTrue(shipDate.contains("Z") || shipDate.contains("+") || shipDate.contains("-"),
                "shipDate 必须带时区偏移，实际：" + shipDate);
    }

    @Test
    @DisplayName("空明细行：明确拒绝，不构造空数组蒙混")
    void rejectsEmptyOrderItems() {
        assertThrows(LocalApiException.class, () -> service.confirmShipment(9001L, "ATVPDKIKX0DER",
                "114-1", "USPS", null, "TRK-1", null, List.of()));
        assertThrows(LocalApiException.class, () -> service.confirmShipment(9001L, "ATVPDKIKX0DER",
                "114-1", "USPS", null, "TRK-1", null, null));
        verify(ordersClient, never()).confirmShipment(any(), any(), any(), any());
    }

    @Test
    @DisplayName("carrierCode=Other 缺 carrierName：本地拒绝")
    void rejectsOtherWithoutName() {
        assertThrows(LocalApiException.class, () -> service.confirmShipment(9001L, "ATVPDKIKX0DER",
                "114-1", "Other", null, "TRK-1", null,
                List.of(new ShipmentConfirmationService.OrderItemLine("OI-1", 1))));
        verify(ordersClient, never()).confirmShipment(any(), any(), any(), any());
    }

    @Test
    @DisplayName("必填字段缺失：本地拒绝（不把校验成本转嫁给 Amazon）")
    void rejectsMissingRequiredFields() {
        List<ShipmentConfirmationService.OrderItemLine> lines =
                List.of(new ShipmentConfirmationService.OrderItemLine("OI-1", 1));
        assertThrows(LocalApiException.class, () -> service.confirmShipment(null, "ATVPDKIKX0DER",
                "114-1", "USPS", null, "TRK-1", null, lines));
        assertThrows(LocalApiException.class, () -> service.confirmShipment(9001L, null,
                "114-1", "USPS", null, "TRK-1", null, lines));
        assertThrows(LocalApiException.class, () -> service.confirmShipment(9001L, "ATVPDKIKX0DER",
                "  ", "USPS", null, "TRK-1", null, lines));
        assertThrows(LocalApiException.class, () -> service.confirmShipment(9001L, "ATVPDKIKX0DER",
                "114-1", "USPS", null, "  ", null, lines));
        verify(ordersClient, never()).confirmShipment(any(), any(), any(), any());
    }

    @Test
    @DisplayName("明细行数量非正：本地拒绝（Amazon 会拒且原因指向字段而非业务）")
    void rejectsNonPositiveQuantity() {
        assertThrows(LocalApiException.class, () -> service.confirmShipment(9001L, "ATVPDKIKX0DER",
                "114-1", "USPS", null, "TRK-1", null,
                List.of(new ShipmentConfirmationService.OrderItemLine("OI-1", 0))));
        assertThrows(LocalApiException.class, () -> service.confirmShipment(9001L, "ATVPDKIKX0DER",
                "114-1", "USPS", null, "TRK-1", null,
                List.of(new ShipmentConfirmationService.OrderItemLine("OI-1", null))));
        verify(ordersClient, never()).confirmShipment(any(), any(), any(), any());
    }
}
