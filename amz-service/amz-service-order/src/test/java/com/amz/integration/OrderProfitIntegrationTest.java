package com.amz.integration;

import com.amz.mapper.ProfitReportMapper;
import com.amz.model.ProfitReport;
import com.amz.profit.ProfitCalculator;
import com.amz.profit.ProfitMQConsumer;
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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 利润 MQ 消费者单元测试（纯 Mockito，不依赖 Spring 容器与 Docker）。
 * <p>
 * 原版本使用 Testcontainers 启动 MySQL + RabbitMQ 容器做端到端集成测试，
 * 需设置环境变量 DOCKER_AVAILABLE=true 才执行；现改为对 {@link ProfitMQConsumer}
 * 的纯单元测试，验证消息消费、幂等去重、异常处理三条核心链路。
 * <p>
 * 通过 {@link Spy} 注入真实 {@link ObjectMapper} 完成 JSON 反序列化，
 * 其余外部依赖（ProfitCalculator、ProfitReportMapper、Channel）均 mock。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("利润 MQ 消费者单元测试")
class OrderProfitIntegrationTest {

    @Mock
    private ProfitCalculator profitCalculator;

    @Mock
    private ProfitReportMapper profitReportMapper;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private Channel channel;

    @InjectMocks
    private ProfitMQConsumer consumer;

    private static final String NORMAL_MESSAGE = "{\"shopId\":1,\"amazonOrderId\":\"ORDER-001\","
            + "\"sku\":\"SKU-001\",\"revenue\":29.99,\"category\":\"Electronics\","
            + "\"sizeTier\":\"small-standard\",\"weightG\":250,\"region\":\"NA\"}";

    /**
     * 正常消息：幂等检查通过（count=0）→ 调用 calculate → insert → basicAck。
     */
    @Test
    @DisplayName("正常消息 → 计算利润并落库 → ack")
    void testNormalMessageShouldCalculateInsertAndAck() throws Exception {
        when(profitReportMapper.selectCount(any())).thenReturn(0L);
        ProfitReport report = buildReport("ORDER-001", "SKU-001", new BigDecimal("29.99"));
        when(profitCalculator.calculate(eq(1L), eq("ORDER-001"), eq("SKU-001"),
                any(BigDecimal.class), eq("Electronics"), eq("small-standard"),
                eq(250), eq("NA"), eq(false))).thenReturn(report);

        consumer.onMessage(NORMAL_MESSAGE, channel, 1L);

        verify(profitCalculator).calculate(eq(1L), eq("ORDER-001"), eq("SKU-001"),
                any(BigDecimal.class), eq("Electronics"), eq("small-standard"),
                eq(250), eq("NA"), eq(false));
        verify(profitReportMapper).insert(report);
        verify(channel).basicAck(1L, false);
        verify(channel, never()).basicNack(anyLong(), anyBoolean(), anyBoolean());
    }

    /**
     * 幂等：同一消息已落库（count>0）→ 直接 ack，不调用 calculate 与 insert。
     */
    @Test
    @DisplayName("幂等消息（已落库）→ 跳过计算直接 ack")
    void testDuplicateMessageShouldSkipAndAck() throws Exception {
        when(profitReportMapper.selectCount(any())).thenReturn(1L);

        consumer.onMessage(NORMAL_MESSAGE, channel, 2L);

        verify(profitCalculator, never()).calculate(anyLong(), anyString(), anyString(),
                any(BigDecimal.class), anyString(), anyString(), anyInt(), anyString(), anyBoolean());
        verify(profitReportMapper, never()).insert(any(ProfitReport.class));
        verify(channel).basicAck(2L, false);
        verify(channel, never()).basicNack(anyLong(), anyBoolean(), anyBoolean());
    }

    /**
     * 异常：JSON 解析失败 → basicNack(requeue=false)，消息转入死信队列。
     */
    @Test
    @DisplayName("非法 JSON → basicNack 不重投")
    void testInvalidJsonShouldNackWithoutRequeue() throws Exception {
        consumer.onMessage("not-a-json", channel, 3L);

        verify(channel).basicNack(3L, false, false);
        verify(channel, never()).basicAck(anyLong(), anyBoolean());
        verify(profitCalculator, never()).calculate(anyLong(), anyString(), anyString(),
                any(BigDecimal.class), anyString(), anyString(), anyInt(), anyString(), anyBoolean());
        verify(profitReportMapper, never()).insert(any(ProfitReport.class));
    }

    /**
     * 异常：profitCalculator 抛异常 → basicNack(requeue=false)，避免毒消息无限重投。
     */
    @Test
    @DisplayName("计算异常 → basicNack 不重投")
    void testCalculateExceptionShouldNack() throws Exception {
        when(profitReportMapper.selectCount(any())).thenReturn(0L);
        when(profitCalculator.calculate(anyLong(), anyString(), anyString(),
                any(BigDecimal.class), anyString(), anyString(), anyInt(), anyString(), anyBoolean()))
                .thenThrow(new RuntimeException("DB 连接失败"));

        consumer.onMessage(NORMAL_MESSAGE, channel, 4L);

        verify(channel).basicNack(4L, false, false);
        verify(channel, never()).basicAck(anyLong(), anyBoolean());
        verify(profitReportMapper, never()).insert(any(ProfitReport.class));
    }

    /**
     * EU 区域消息：isEU 应为 true，验证 region 字段正确传递给 calculate。
     */
    @Test
    @DisplayName("EU 区域消息 → isEU=true 传入 calculate")
    void testEuRegionMessageShouldPassIsEuTrue() throws Exception {
        when(profitReportMapper.selectCount(any())).thenReturn(0L);
        ProfitReport report = buildReport("ORDER-EU", "SKU-EU", new BigDecimal("49.99"));
        String euMessage = "{\"shopId\":2,\"amazonOrderId\":\"ORDER-EU\","
                + "\"sku\":\"SKU-EU\",\"revenue\":49.99,\"category\":\"Electronics\","
                + "\"sizeTier\":\"small-standard\",\"weightG\":300,\"region\":\"EU\"}";
        when(profitCalculator.calculate(eq(2L), eq("ORDER-EU"), eq("SKU-EU"),
                any(BigDecimal.class), eq("Electronics"), eq("small-standard"),
                eq(300), eq("EU"), eq(true))).thenReturn(report);

        consumer.onMessage(euMessage, channel, 5L);

        verify(profitCalculator).calculate(eq(2L), eq("ORDER-EU"), eq("SKU-EU"),
                any(BigDecimal.class), eq("Electronics"), eq("small-standard"),
                eq(300), eq("EU"), eq(true));
        verify(profitReportMapper).insert(report);
        verify(channel).basicAck(5L, false);
    }

    private ProfitReport buildReport(String orderId, String sku, BigDecimal revenue) {
        ProfitReport report = new ProfitReport();
        report.setShopId(1L);
        report.setAmazonOrderId(orderId);
        report.setSku(sku);
        report.setRevenue(revenue);
        report.setNetProfit(new BigDecimal("5.00"));
        report.setDataComplete(true);
        return report;
    }
}
