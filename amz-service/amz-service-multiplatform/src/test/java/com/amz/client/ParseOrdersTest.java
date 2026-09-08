package com.amz.client;

import com.amz.model.UnifiedOrder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 三平台订单解析测试（B3）。
 * <p>
 * 回归：parseOrders 曾只取明细首行，多商品订单静默丢数据；
 * 现全量保留于 {@code UnifiedOrder.items}，头字段回填首行保持兼容。
 */
@DisplayName("三平台订单解析测试")
class ParseOrdersTest {

    @Test
    @DisplayName("Temu：多明细全量保留，头字段回填首行")
    void temuKeepsAllItems() throws Exception {
        String resp = "{\"code\":0,\"data\":{\"order_list\":[{"
                + "\"order_sn\":\"T1\",\"buyer_name\":\"b\",\"country\":\"US\","
                + "\"sku_list\":[{\"sku\":\"S1\",\"product_name\":\"P1\",\"sku_count\":2},"
                + "{\"sku\":\"S2\",\"product_name\":\"P2\",\"sku_count\":1}],"
                + "\"pay_amount\":\"30.00\",\"currency\":\"USD\",\"created_time\":\"2026-01-01\"}]}}";

        List<UnifiedOrder> orders = new TemuRealClient().parseOrders(resp, 1L);

        assertEquals(1, orders.size());
        UnifiedOrder o = orders.get(0);
        assertNotNull(o.getItems());
        assertEquals(2, o.getItems().size());
        assertEquals("S2", o.getItems().get(1).getSku());
        assertEquals(1, o.getItems().get(1).getQuantity());
        // 头字段兼容：首行回填；pay_amount 文本节点不得被 isEmpty 误判丢弃
        assertEquals("S1", o.getSku());
        assertEquals(2, o.getQuantity());
        assertEquals(new BigDecimal("30.00"), o.getOriginalAmount());
    }

    @Test
    @DisplayName("Shein：ordnetId 回退 + 多明细保留")
    void sheinKeepsAllItems() throws Exception {
        String resp = "{\"code\":0,\"data\":{\"orders\":[{"
                + "\"orderId\":\"SH1\","
                + "\"skuList\":[{\"sku\":\"A\",\"productName\":\"PA\",\"quantity\":3},"
                + "{\"sku\":\"B\",\"productName\":\"PB\",\"quantity\":1}],"
                + "\"payAmount\":\"10\",\"currency\":\"EUR\",\"createTime\":\"x\"}]}}";

        List<UnifiedOrder> orders = new SheinRealClient().parseOrders(resp, 1L);

        assertEquals(1, orders.size());
        UnifiedOrder o = orders.get(0);
        assertEquals("SH1", o.getPlatformOrderNo());
        assertEquals(2, o.getItems().size());
        assertEquals("B", o.getItems().get(1).getSku());
        assertEquals("A", o.getSku());
        assertEquals(3, o.getQuantity());
        assertEquals(new BigDecimal("10"), o.getOriginalAmount());
    }

    @Test
    @DisplayName("TikTok：状态映射 + 多明细保留")
    void tiktokKeepsAllItems() throws Exception {
        String resp = "{\"code\":0,\"data\":{\"orders\":[{"
                + "\"order_id\":\"K1\",\"status\":\"AWAITING_SHIPMENT\","
                + "\"buyer\":{\"username\":\"u\"},\"recipient_address\":{\"country\":\"GB\"},"
                + "\"product_list\":[{\"sku_id\":\"K-S1\",\"product_name\":\"KP1\",\"quantity\":2},"
                + "{\"sku_id\":\"K-S2\",\"product_name\":\"KP2\",\"quantity\":4}],"
                + "\"payment\":{\"amount\":\"50\",\"currency\":\"GBP\"},\"create_time\":1700000000}]}}";

        List<UnifiedOrder> orders = new TikTokRealClient().parseOrders(resp, 1L);

        assertEquals(1, orders.size());
        UnifiedOrder o = orders.get(0);
        assertEquals("PAID", o.getStatus());
        assertEquals(2, o.getItems().size());
        assertEquals("K-S2", o.getItems().get(1).getSku());
        assertEquals(4, o.getItems().get(1).getQuantity());
        assertEquals("K-S1", o.getSku());
        assertEquals(new BigDecimal("50"), o.getOriginalAmount());
    }
}
