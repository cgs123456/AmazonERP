package com.amz.service.impl;

import com.amz.client.FinanceServiceFeignClient;
import com.amz.mapper.OrderMapper;
import com.amz.model.dto.OrderSyncDto;
import com.amz.model.pojo.Order;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 订单同步凭证分支测试（纯 Mockito，不依赖数据库/MQ）。
 * <p>
 * 回归 B1：syncAmazonOrder 默认改发 MQ（Seata 全局事务不再跨服务持有锁），
 * finance.voucher.async=false 时回退同步 Feign。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("订单同步凭证分支测试")
class OrderServiceImplTest {

    @Mock
    private OrderMapper orderMapper;

    @Mock
    private RabbitTemplate rabbitTemplate;

    @Mock
    private FinanceServiceFeignClient financeServiceFeignClient;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks
    private OrderServiceImpl orderService;

    private static OrderSyncDto syncDto() {
        OrderSyncDto dto = new OrderSyncDto();
        dto.setAmazonOrderId("114-0000000-0000001");
        dto.setShopId(1L);
        dto.setTotalAmount(new BigDecimal("29.99"));
        dto.setCurrency("USD");
        return dto;
    }

    @Test
    @DisplayName("默认 MQ 路径：发凭证消息，不调 Feign")
    void syncPublishesVoucherMessageByDefault() {
        ReflectionTestUtils.setField(orderService, "voucherAsyncEnabled", true);
        when(orderMapper.selectCount(any())).thenReturn(0L);
        when(orderMapper.insert(any(Order.class))).thenReturn(1);

        orderService.syncAmazonOrder(syncDto());

        ArgumentCaptor<Message> captor = ArgumentCaptor.forClass(Message.class);
        verify(rabbitTemplate).send(
                eq("amz.finance.voucher.exchange"), eq("amz.finance.voucher"), captor.capture());
        String body = new String(captor.getValue().getBody(), StandardCharsets.UTF_8);
        assertTrue(body.contains("114-0000000-0000001"), "消息体应携带订单号：" + body);
        assertTrue(body.contains("\"currency\":\"USD\""), "消息体应携带币种：" + body);
        verify(financeServiceFeignClient, never()).generateOrderVoucher(
                any(), anyString(), any(), anyString());
    }

    @Test
    @DisplayName("回退路径：finance.voucher.async=false 时调同步 Feign，不发 MQ")
    void syncFallsBackToFeignWhenAsyncDisabled() {
        ReflectionTestUtils.setField(orderService, "voucherAsyncEnabled", false);
        when(orderMapper.selectCount(any())).thenReturn(0L);
        when(orderMapper.insert(any(Order.class))).thenReturn(1);

        orderService.syncAmazonOrder(syncDto());

        verify(financeServiceFeignClient).generateOrderVoucher(
                eq(1L), eq("114-0000000-0000001"), eq(new BigDecimal("29.99")), eq("USD"));
        verify(rabbitTemplate, never()).send(anyString(), anyString(), any(Message.class));
    }
}
