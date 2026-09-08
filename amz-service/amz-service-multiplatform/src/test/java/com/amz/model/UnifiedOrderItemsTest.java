package com.amz.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 订单明细序列化工具测试（B3，无 Spring、无网络）。
 */
@DisplayName("订单明细序列化测试")
class UnifiedOrderItemsTest {

    @Test
    @DisplayName("空明细序列化为 null（列保持 NULL）")
    void emptySerializesToNull() {
        assertNull(UnifiedOrderItems.toJson(null));
        assertNull(UnifiedOrderItems.toJson(List.of()));
    }

    @Test
    @DisplayName("序列化/反序列化往返一致")
    void roundTrip() {
        UnifiedOrder order = new UnifiedOrder();
        order.addItem("S1", "P1", 2);
        order.addItem("S2", "P2", 1);

        String json = UnifiedOrderItems.toJson(order.getItems());
        List<UnifiedOrderItem> back = UnifiedOrderItems.fromJson(json);

        assertEquals(2, back.size());
        assertEquals("S2", back.get(1).getSku());
        assertEquals(1, back.get(1).getQuantity());
    }

    @Test
    @DisplayName("非法 JSON 反序列化降级为空列表，不抛异常")
    void brokenJsonDegradesToEmpty() {
        assertTrue(UnifiedOrderItems.fromJson("{broken").isEmpty());
        assertTrue(UnifiedOrderItems.fromJson(null).isEmpty());
    }

    @Test
    @DisplayName("老数据（无 JSON）按头字段派生单条明细")
    void legacyDerivesFromHeader() {
        UnifiedOrder order = new UnifiedOrder();
        order.setSku("OLD");
        order.setProductName("Old Product");
        order.setQuantity(5);

        UnifiedOrderItems.ensureItems(order);

        assertEquals(1, order.getItems().size());
        assertEquals("OLD", order.getItems().get(0).getSku());
        assertEquals(5, order.getItems().get(0).getQuantity());
    }

    @Test
    @DisplayName("addItem 首行回填头字段，不覆盖预设值")
    void addItemFillsHeaderOnce() {
        UnifiedOrder order = new UnifiedOrder();
        order.setSku("PRESET");
        order.addItem("S1", "P1", 2);
        order.addItem("S2", "P2", 1);

        assertEquals("PRESET", order.getSku());
        assertEquals("P1", order.getProductName());
        assertEquals(2, order.getItems().size());
    }
}
