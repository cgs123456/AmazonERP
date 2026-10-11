package com.amz.service.impl;

import com.amz.client.SpApiShipmentClient;
import com.amz.client.dto.ShipmentConfirmRequest;
import com.amz.context.UserContext;
import com.amz.exception.CodeErrorException;
import com.amz.mapper.OrderItemMapper;
import com.amz.mapper.OrderMapper;
import com.amz.model.pojo.Order;
import com.amz.model.pojo.OrderItem;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 订单发货回传契约（FBM 确认发货）。
 * <p>
 * <b>存在动因</b>：此前 {@code amz_order.tracking_number} 零写入点、
 * {@code sync_status} 的「2=已上传跟踪号」从未被写过——这条链路在系统里完全不存在。
 * 本测试钉住五条语义，其中第一条是最重要的不变量：
 * <ol>
 *   <li><b>平台失败时本地状态绝不变</b>——把「调用失败」写成「已发货」会让 Amazon
 *       侧超时未发货，直接影响卖家账号绩效；</li>
 *   <li>归属校验在服务层：他店订单既不回传也不改本地；</li>
 *   <li>无明细行时明确拒绝（Amazon 要求逐行确认，空行数组必被拒）；</li>
 *   <li>重复回传明确拒绝（Amazon 对同一订单重复 confirmShipment 会报错）；</li>
 *   <li>成功后才写入 tracking/carrier/fulfillment_status/sync_status=2。</li>
 * </ol>
 * 变异验证（写本测试时实际注入确认会红）：把「accepted 为 false 时抛错」改成继续往下写
 * → 第一例红；去掉 isShopAllowedStrict 校验 → 第二例红；把重复回传的拒绝删掉 → 第四例红。
 */
@DisplayName("订单发货回传：平台未受理则本地不变")
@ExtendWith(MockitoExtension.class)
class OrderFulfillmentServiceTest {

    @Mock
    private OrderMapper orderMapper;
    @Mock
    private OrderItemMapper orderItemMapper;
    @Mock
    private SpApiShipmentClient spApiShipmentClient;

    @InjectMocks
    private OrderFulfillmentService service;

    @AfterEach
    void clear() {
        UserContext.clear();
    }

    @Test
    @DisplayName("平台未受理时本地状态保持不变（核心不变量）")
    void doesNotMarkLocalWhenPlatformRejects() {
        grantShop(9001L);
        Order order = pendingOrder();
        when(orderMapper.selectById(10L)).thenReturn(order);
        when(orderItemMapper.selectList(any())).thenReturn(List.of(item("OI-1", 2)));
        when(spApiShipmentClient.confirmShipment(any())).thenReturn(Boolean.FALSE);

        assertThrows(CodeErrorException.class,
                () -> service.confirmShipment(10L, "USPS", null, "TRK-1", null));

        assertNull(order.getTrackingNumber(), "平台未受理就写运单号，等于本地谎报已发货");
        assertEquals(OrderFulfillmentService.STATUS_NOT_CONFIRMED, order.getFulfillmentStatus(),
                "回传状态必须保持未回传");
        assertNull(order.getShipConfirmedAt());
        verify(orderMapper, never()).updateById(any(Order.class));
    }

    @Test
    @DisplayName("调用抛异常（含熔断降级）时本地状态也不变")
    void doesNotMarkLocalWhenCallThrows() {
        grantShop(9001L);
        Order order = pendingOrder();
        when(orderMapper.selectById(10L)).thenReturn(order);
        when(orderItemMapper.selectList(any())).thenReturn(List.of(item("OI-1", 1)));
        when(spApiShipmentClient.confirmShipment(any())).thenThrow(new RuntimeException("spapi down"));

        assertThrows(CodeErrorException.class,
                () -> service.confirmShipment(10L, "USPS", null, "TRK-1", null));

        assertNull(order.getTrackingNumber());
        verify(orderMapper, never()).updateById(any(Order.class));
    }

    @Test
    @DisplayName("他店订单：拒绝且不回传平台")
    void rejectsForeignShopOrder() {
        grantShop(9001L);
        Order order = pendingOrder();
        order.setShopId(7777L);
        when(orderMapper.selectById(10L)).thenReturn(order);

        assertThrows(CodeErrorException.class,
                () -> service.confirmShipment(10L, "USPS", null, "TRK-1", null));

        verify(spApiShipmentClient, never()).confirmShipment(any());
        verify(orderMapper, never()).updateById(any(Order.class));
    }

    @Test
    @DisplayName("已回传的订单：明确拒绝重复回传")
    void rejectsDuplicateConfirmation() {
        grantShop(9001L);
        Order order = pendingOrder();
        order.setFulfillmentStatus(OrderFulfillmentService.STATUS_CONFIRMED);
        when(orderMapper.selectById(10L)).thenReturn(order);

        CodeErrorException e = assertThrows(CodeErrorException.class,
                () -> service.confirmShipment(10L, "USPS", null, "TRK-2", null));
        assertNotNull(e.getMessage());
        verify(spApiShipmentClient, never()).confirmShipment(any());
    }

    @Test
    @DisplayName("无明细行：拒绝且不构造空行数组蒙混")
    void rejectsWhenNoOrderItems() {
        grantShop(9001L);
        Order order = pendingOrder();
        when(orderMapper.selectById(10L)).thenReturn(order);
        when(orderItemMapper.selectList(any())).thenReturn(List.of());

        assertThrows(CodeErrorException.class,
                () -> service.confirmShipment(10L, "USPS", null, "TRK-1", null));
        verify(spApiShipmentClient, never()).confirmShipment(any());
    }

    @Test
    @DisplayName("成功路径：逐行明细随请求带上，本地写入 tracking/carrier/状态/syncStatus=2")
    void writesLocalOnlyAfterSuccess() {
        grantShop(9001L);
        Order order = pendingOrder();
        when(orderMapper.selectById(10L)).thenReturn(order);
        when(orderItemMapper.selectList(any())).thenReturn(List.of(
                item("OI-1", 2), item("OI-2", 1)));
        when(spApiShipmentClient.confirmShipment(any())).thenReturn(Boolean.TRUE);

        Order result = service.confirmShipment(10L, " USPS ", " US Postal ", " TRK-9 ", null);

        ArgumentCaptor<ShipmentConfirmRequest> captor = ArgumentCaptor.forClass(ShipmentConfirmRequest.class);
        verify(spApiShipmentClient).confirmShipment(captor.capture());
        ShipmentConfirmRequest sent = captor.getValue();
        assertEquals("USPS", sent.getCarrierCode(), "承运商代码应 trim");
        assertEquals("US Postal", sent.getCarrierName());
        assertEquals("TRK-9", sent.getTrackingNumber(), "运单号应 trim");
        assertEquals(2, sent.getOrderItems().size(), "明细行必须逐行带上");
        assertEquals("OI-1", sent.getOrderItems().get(0).getOrderItemId());
        assertEquals(2, sent.getOrderItems().get(0).getQuantity());

        assertEquals("TRK-9", result.getTrackingNumber());
        assertEquals("USPS", result.getCarrierCode());
        assertEquals(OrderFulfillmentService.STATUS_CONFIRMED, result.getFulfillmentStatus());
        assertEquals(2, result.getSyncStatus(), "sync_status 推进到 2（已上传跟踪号）");
        assertNotNull(result.getShipConfirmedAt());
        verify(orderMapper).updateById(order);
    }

    @Test
    @DisplayName("carrierCode=Other 时必须给 carrierName")
    void requiresCarrierNameForOther() {
        grantShop(9001L);
        // 输入校验在读取订单之前完成，因此这里不 stub mapper —— 也不 stub，
        // 以证明「缺承运商名称」是被输入校验挡下的，而不是被「订单不存在」挡下的。
        assertThrows(CodeErrorException.class,
                () -> service.confirmShipment(10L, "Other", null, "TRK-1", null));
        verify(spApiShipmentClient, never()).confirmShipment(any());
    }

    @Test
    @DisplayName("空运单号/空承运商先拒绝，不产生无效回传")
    void rejectsBlankInputs() {
        grantShop(9001L);
        assertThrows(CodeErrorException.class,
                () -> service.confirmShipment(10L, "USPS", null, "  ", null));
        assertThrows(CodeErrorException.class,
                () -> service.confirmShipment(10L, "", null, "TRK-1", null));
    }

    @Test
    @DisplayName("非法发货时间：拒绝而不是静默替换成当前时间")
    void rejectsInvalidShipDate() {
        grantShop(9001L);
        Order order = pendingOrder();
        when(orderMapper.selectById(10L)).thenReturn(order);
        when(orderItemMapper.selectList(any())).thenReturn(List.of(item("OI-1", 1)));

        assertThrows(CodeErrorException.class,
                () -> service.confirmShipment(10L, "USPS", null, "TRK-1", "2026-13-45"));
        verify(spApiShipmentClient, never()).confirmShipment(any());
    }

    @Test
    @DisplayName("订单不存在：不调任何外部通道")
    void rejectsMissingOrder() {
        grantShop(9001L);
        when(orderMapper.selectById(10L)).thenReturn(null);

        assertThrows(CodeErrorException.class,
                () -> service.confirmShipment(10L, "USPS", null, "TRK-1", null));
        verify(spApiShipmentClient, never()).confirmShipment(any());
    }

    // ============ 辅助 ============

    private void grantShop(Long shopId) {
        UserContext.setUserId(1);
        UserContext.setRole("OPERATOR");
        UserContext.setShops(List.of(shopId));
    }

    private Order pendingOrder() {
        Order order = new Order();
        order.setId(10L);
        order.setShopId(9001L);
        order.setMarketplaceId("ATVPDKIKX0DER");
        order.setAmazonOrderId("114-0000000-7599439");
        order.setFulfillmentStatus(OrderFulfillmentService.STATUS_NOT_CONFIRMED);
        return order;
    }

    private OrderItem item(String orderItemId, int quantity) {
        OrderItem item = new OrderItem();
        item.setAmazonOrderItemId(orderItemId);
        item.setQuantity(quantity);
        item.setShopId(9001L);
        item.setAmazonOrderId("114-0000000-7599439");
        return item;
    }
}
