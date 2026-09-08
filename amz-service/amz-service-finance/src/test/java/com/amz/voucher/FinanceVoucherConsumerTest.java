package com.amz.voucher;

import com.amz.service.FinanceService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 凭证生成 MQ 消费者测试（纯 Mockito，不依赖数据库/MQ）。
 * <p>
 * 覆盖 B1：正常消息 → 调 generateOrderVoucher（幂等）→ ack；
 * 非法 JSON / 缺字段 → nack 不重投（经 DLX 转死信）。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("凭证生成 MQ 消费者测试")
class FinanceVoucherConsumerTest {

    @Mock
    private FinanceService financeService;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private Channel channel;

    @InjectMocks
    private FinanceVoucherConsumer consumer;

    @Test
    @DisplayName("正常消息 → 生成凭证并 ack")
    void testNormalMessageShouldGenerateAndAck() throws Exception {
        String json = "{\"shopId\":1,\"amazonOrderId\":\"114-xxx\","
                + "\"totalAmount\":29.99,\"currency\":\"USD\"}";
        when(financeService.generateOrderVoucher(
                eq(1L), eq("114-xxx"), eq(new BigDecimal("29.99")), eq("USD")))
                .thenReturn(null);

        consumer.onMessage(json, channel, 1L);

        verify(financeService).generateOrderVoucher(
                eq(1L), eq("114-xxx"), eq(new BigDecimal("29.99")), eq("USD"));
        verify(channel).basicAck(1L, false);
    }

    @Test
    @DisplayName("非法 JSON → basicNack 不重投")
    void testInvalidJsonShouldNackWithoutRequeue() throws Exception {
        consumer.onMessage("{broken json", channel, 2L);

        verify(channel).basicNack(2L, false, false);
    }

    @Test
    @DisplayName("缺字段消息 → basicNack 不重投")
    void testMissingFieldsShouldNackWithoutRequeue() throws Exception {
        consumer.onMessage("{\"shopId\":1}", channel, 3L);

        verify(channel).basicNack(3L, false, false);
    }
}
