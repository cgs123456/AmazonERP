package com.amz.voucher;

import com.amz.mq.VoucherMessage;
import com.amz.service.FinanceService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * 凭证生成 MQ 消费者（B1）。
 * <p>
 * 监听订单落库消息，调用 {@code generateOrderVoucher} 生成会计凭证。
 * 幂等：generateOrderVoucher 按 (shopId, sourceType=ORDER, sourceNo) 去重，
 * 重复投递直接返回既有凭证（视为成功，直接 ack）。
 * <p>
 * 手动 ack 模式：方法正常完成 basicAck；任何异常 basicNack(requeue=false)，
 * 消息经 DLX 转入死信队列（{@link FinanceVoucherMqConfig#voucherDlqQueue()}），避免毒消息无限重投或被静默丢弃。
 */
@Slf4j
@Component
public class FinanceVoucherConsumer {

    @Autowired
    private FinanceService financeService;

    @Autowired
    private ObjectMapper objectMapper;

    @RabbitListener(queues = FinanceVoucherMqConfig.VOUCHER_QUEUE)
    public void onMessage(String message,
                          Channel channel,
                          @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) throws IOException {
        try {
            VoucherMessage msg = objectMapper.readValue(message, VoucherMessage.class);
            if (msg.shopId() == null || msg.amazonOrderId() == null || msg.amazonOrderId().isBlank()
                    || msg.totalAmount() == null || msg.currency() == null || msg.currency().isBlank()) {
                log.error("凭证消息字段缺失，转入 DLQ：{}", message);
                channel.basicNack(deliveryTag, false, false);
                return;
            }

            financeService.generateOrderVoucher(
                    msg.shopId(), msg.amazonOrderId(), msg.totalAmount(), msg.currency());
            log.info("凭证生成消费成功：shopId={} orderNo={}", msg.shopId(), msg.amazonOrderId());

            channel.basicAck(deliveryTag, false);
        } catch (Exception e) {
            // 记录完整异常信息（含消息体），nack 不 requeue，消息经 DLX 转入死信队列
            log.error("处理凭证消息失败，消息转入 DLQ：{}", message, e);
            channel.basicNack(deliveryTag, false, false);
        }
    }
}
