package com.amz.profit;

import com.amz.mapper.ProfitReportMapper;
import com.amz.model.ProfitReport;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * 利润核算 MQ 消费者
 * 监听订单同步消息，计算利润并落库。
 * 幂等：按 shopId + amazonOrderId + sku 去重。
 * 异常吞掉不重试，避免毒消息。
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
    public void onMessage(String message) {
        try {
            ProfitMessage msg = objectMapper.readValue(message, ProfitMessage.class);

            // 幂等性检查：已存在则跳过
            Long count = profitReportMapper.selectCount(new LambdaQueryWrapper<ProfitReport>()
                    .eq(ProfitReport::getShopId, msg.shopId())
                    .eq(ProfitReport::getAmazonOrderId, msg.amazonOrderId())
                    .eq(ProfitReport::getSku, msg.sku()));
            if (count != null && count > 0) {
                log.info("利润报告已存在，跳过：shopId={}, order={}, sku={}",
                        msg.shopId(), msg.amazonOrderId(), msg.sku());
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
            log.info("利润报告落库成功：shopId={}, order={}, sku={}, netProfit={}",
                    msg.shopId(), msg.amazonOrderId(), msg.sku(), report.getNetProfit());
        } catch (Exception e) {
            // 异常吞掉，消息会被 ack，不重试，避免毒消息
            log.error("处理利润消息失败，丢弃消息（避免毒消息）：{}", message, e);
        }
    }
}
