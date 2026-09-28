package com.amz.service.impl;

import com.amz.client.FinanceServiceFeignClient;
import com.amz.mapper.OrderItemMapper;
import com.amz.mapper.OrderMapper;
import com.amz.model.dto.OrderItemSyncDto;
import com.amz.model.dto.OrderSyncDto;
import com.amz.model.pojo.Order;
import com.amz.model.pojo.OrderItem;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 订单明细行落库测试（V5 {@code amz_order_item}，纯 Mockito，不依赖数据库）。
 * <p>
 * 覆盖四类必须成立的行为：
 * 1. 新订单的明细行全部落库，金额按字符串精确解析（禁止 double 中转）；
 * 2. 重复同步走 update 而不是 insert（唯一键 uk_order_item 的幂等语义）；
 * 3. 订单已存在（幂等跳过）时仍然补写明细——否则首轮拉取失败的明细会永久缺失；
 * 4. 缺 shopId 时**不写**明细，绝不用默认值伪造归属。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("订单明细行落库（V5 amz_order_item）")
class OrderItemPersistenceTest {

    @Mock
    private OrderMapper orderMapper;

    @Mock
    private OrderItemMapper orderItemMapper;

    @Mock
    private FinanceServiceFeignClient financeServiceFeignClient;

    @Mock
    private RabbitTemplate rabbitTemplate;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks
    private OrderServiceImpl orderService;

    private static OrderItemSyncDto item(String itemId, String sku, String price) {
        OrderItemSyncDto item = new OrderItemSyncDto();
        item.setAmazonOrderItemId(itemId);
        item.setAsin("B0TEST00001");
        item.setSellerSku(sku);
        item.setTitle("Test Product");
        item.setQuantity(2);
        item.setItemPrice(price == null ? null : new BigDecimal(price));
        item.setItemTax(new BigDecimal("1.60"));
        item.setPromotionDiscount(new BigDecimal("2.00"));
        return item;
    }

    private static OrderSyncDto syncDto(Long shopId, List<OrderItemSyncDto> items) {
        OrderSyncDto dto = new OrderSyncDto();
        dto.setAmazonOrderId("114-0000000-0000001");
        dto.setShopId(shopId);
        dto.setMarketplaceId("ATVPDKIKX0DER");
        dto.setTotalAmount(new BigDecimal("39.98"));
        dto.setCurrency("USD");
        dto.setFulfillmentChannel("AFN");
        dto.setOrderItems(items);
        return dto;
    }

    @Test
    @DisplayName("新订单：明细行全部落库，金额精确，币种/渠道回退订单级")
    void persistsAllItemsForNewOrder() {
        ReflectionTestUtils.setField(orderService, "voucherAsyncEnabled", true);
        when(orderMapper.selectCount(any())).thenReturn(0L);
        when(orderMapper.insert(any(Order.class))).thenReturn(1);

        orderService.syncAmazonOrder(syncDto(1L,
                List.of(item("ITEM-1", "SKU-1", "19.99"), item("ITEM-2", "SKU-2", "19.99"))));

        ArgumentCaptor<OrderItem> captor = ArgumentCaptor.forClass(OrderItem.class);
        verify(orderItemMapper, times(2)).insert(captor.capture());
        List<OrderItem> saved = captor.getAllValues();

        OrderItem first = saved.get(0);
        assertEquals("ITEM-1", first.getAmazonOrderItemId());
        assertEquals("SKU-1", first.getSellerSku());
        assertEquals(2, first.getQuantity());
        assertEquals(new BigDecimal("19.99"), first.getItemPrice());
        assertEquals(new BigDecimal("1.60"), first.getItemTax());
        // 明细行未带币种/渠道时回退订单级取值
        assertEquals("USD", first.getCurrency());
        assertEquals("AFN", first.getFulfillmentChannel());
        assertEquals("ATVPDKIKX0DER", first.getMarketplaceId());
        assertEquals("114-0000000-0000001", first.getAmazonOrderId());
        assertEquals(1L, first.getShopId());
        // product_id 不可猜测映射，必须保持 NULL
        assertNull(first.getProductId());
    }

    @Test
    @DisplayName("重复同步：已存在的明细行走 update，不产生重复行")
    void updatesExistingItemInsteadOfInsert() {
        ReflectionTestUtils.setField(orderService, "voucherAsyncEnabled", true);
        when(orderMapper.selectCount(any())).thenReturn(0L);
        when(orderMapper.insert(any(Order.class))).thenReturn(1);

        OrderItem existing = new OrderItem();
        existing.setId(99L);
        when(orderItemMapper.selectOne(any())).thenReturn(existing);

        orderService.syncAmazonOrder(syncDto(1L, List.of(item("ITEM-1", "SKU-1", "19.99"))));

        ArgumentCaptor<OrderItem> captor = ArgumentCaptor.forClass(OrderItem.class);
        verify(orderItemMapper).updateById(captor.capture());
        assertEquals(99L, captor.getValue().getId(), "update 必须带上已有主键");
        verify(orderItemMapper, never()).insert(any(OrderItem.class));
    }

    @Test
    @DisplayName("订单已存在（幂等跳过）时仍补写明细，避免首轮拉取失败导致明细永久缺失")
    void backfillsItemsWhenOrderAlreadyExists() {
        ReflectionTestUtils.setField(orderService, "voucherAsyncEnabled", true);
        when(orderMapper.selectCount(any())).thenReturn(1L);

        orderService.syncAmazonOrder(syncDto(1L, List.of(item("ITEM-1", "SKU-1", "19.99"))));

        verify(orderMapper, never()).insert(any(Order.class));
        verify(orderItemMapper).insert(any(OrderItem.class));
    }

    @Test
    @DisplayName("缺 shopId：不写明细，绝不用默认值伪造店铺归属")
    void skipsItemsWhenShopIdMissing() {
        ReflectionTestUtils.setField(orderService, "voucherAsyncEnabled", true);
        when(orderMapper.selectCount(any())).thenReturn(0L);
        when(orderMapper.insert(any(Order.class))).thenReturn(1);

        orderService.syncAmazonOrder(syncDto(null, List.of(item("ITEM-1", "SKU-1", "19.99"))));

        verify(orderItemMapper, never()).insert(any(OrderItem.class));
        verify(orderItemMapper, never()).updateById(any(OrderItem.class));
    }

    @Test
    @DisplayName("缺 amazonOrderItemId 的行被跳过（该字段是唯一键组成部分，不可填空）")
    void skipsItemWithoutOrderItemId() {
        ReflectionTestUtils.setField(orderService, "voucherAsyncEnabled", true);
        when(orderMapper.selectCount(any())).thenReturn(0L);
        when(orderMapper.insert(any(Order.class))).thenReturn(1);
        OrderItemSyncDto broken = new OrderItemSyncDto();
        broken.setSellerSku("SKU-1");

        orderService.syncAmazonOrder(syncDto(1L, List.of(broken)));

        verify(orderItemMapper, never()).insert(any(OrderItem.class));
    }

    @Test
    @DisplayName("没有明细数据时不产生任何明细写入（不写占位行）")
    void noItemsMeansNoItemWrites() {
        ReflectionTestUtils.setField(orderService, "voucherAsyncEnabled", true);
        when(orderMapper.selectCount(any())).thenReturn(0L);
        when(orderMapper.insert(any(Order.class))).thenReturn(1);

        orderService.syncAmazonOrder(syncDto(1L, null));

        verify(orderItemMapper, never()).insert(any(OrderItem.class));
        verify(orderItemMapper, never()).selectOne(any());
    }
}
