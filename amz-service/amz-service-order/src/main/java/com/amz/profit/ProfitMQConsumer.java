package com.amz.profit;

import com.amz.mapper.ProfitReportMapper;
import com.amz.model.ProfitReport;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.math.BigDecimal;

/**
 * 利润核算 MQ 消费者
 * <p>
 * 监听订单同步消息，计算利润并落库。
 * 幂等：按 shopId + amazonOrderId + sku 去重。
 * <p>
 * 手动 ack 模式：方法正常完成 basicAck；任何异常 basicNack(requeue=false)，
 * 消息经 DLX 转入死信队列（{@link ProfitMQConfig#profitDlqQueue()}），避免毒消息无限重投或被静默丢弃。
 */
@Slf4j
@Component
public class ProfitMQConsumer {

    @Autowired
    private ProfitCalculator profitCalculator;

    @Autowired
    private ProfitReportMapper profitReportMapper;

    @Autowired
    private ObjectMapper objectMapper;

    /**
     * 利润消息体（JSON 反序列化）
     * 示例：{"shopId":1, "amazonOrderId":"114-xxx", "sku":"SKU-001",
     *       "revenue":29.99, "category":"Electronics",
     *       "sizeTier":"small-standard", "weightG":250, "region":"NA"}
     */
    public record ProfitMessage(
            Long shopId,
            String amazonOrderId,
            String sku,
            BigDecimal revenue,
            String category,
            String sizeTier,
            Integer weightG,
            String region
    ) {
    }

    @RabbitListener(queues = ProfitMQConfig.PROFIT_QUEUE)
    public void onMessage(String message,
                          Channel channel,
                          @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) throws IOException {
        try {
            ProfitMessage msg = objectMapper.readValue(message, ProfitMessage.class);

            // 幂等性检查：已存在则跳过（视为成功，直接 ack）
            Long count = profitReportMapper.selectCount(new LambdaQueryWrapper<ProfitReport>()
                    .eq(ProfitReport::getShopId, msg.shopId())
                    .eq(ProfitReport::getAmazonOrderId, msg.amazonOrderId())
                    .eq(ProfitReport::getSku, msg.sku()));
            if (count != null && count > 0) {
                log.info("利润报告已存在，跳过：shopId={}, order={}, sku={}",
                        msg.shopId(), msg.amazonOrderId(), msg.sku());
                channel.basicAck(deliveryTag, false);
                return;
            }

            boolean isEU = "EU".equalsIgnoreCase(msg.region());
            int weightG = msg.weightG() != null ? msg.weightG() : 0;
            BigDecimal revenue = msg.revenue() != null ? msg.revenue() : BigDecimal.ZERO;

            ProfitReport report = profitCalculator.calculate(
                    msg.shopId(),
                    msg.amazonOrderId(),
                    msg.sku(),
                    revenue,
                    msg.category(),
                    msg.sizeTier(),
                    weightG,
                    msg.region(),
                    isEU
            );

            profitReportMapper.insert(report);
            log.info("利润报告落库成功：shopId={}, order={}, sku={}, netProfit={}, dataComplete={}",
                    msg.shopId(), msg.amazonOrderId(), msg.sku(), report.getNetProfit(), report.getDataComplete());

            channel.basicAck(deliveryTag, false);
        } catch (Exception e) {
            // 记录完整异常信息（含消息体），nack 不 requeue，消息经 DLX 转入死信队列
            log.error("处理利润消息失败，消息转入 DLQ：{}", message, e);
            channel.basicNack(deliveryTag, false, false);
        }
    }
}
