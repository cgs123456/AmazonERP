package com.amz.mq.consumer;

import com.amz.constant.MqConstant;
import com.amz.model.dto.BuyDto;
import com.amz.model.dto.OrderDto;
import com.amz.service.OrderService;
import com.rabbitmq.client.Channel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.Message;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class OrderConsumer {

    @Autowired
    private OrderService orderService;

    @RabbitListener(queues = MqConstant.SAVE_ORDER_QUEUE)
    public void onMessage(Message<BuyDto> message, Channel channel, @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag){
        BuyDto buyDto = message.getPayload();

        OrderDto orderDto = new OrderDto();
        orderDto.setProductId(buyDto.getProductId());
        orderDto.setSelectAttributes(buyDto.getSelectAttributes());
        orderDto.setUserId(buyDto.getUserId());

        // 使用 deliveryTag 作为消息ID（deliveryTag 在同一条消息的多次投递中相同，保证幂等性）
        String messageId = String.valueOf(deliveryTag);
        
        try {
            // 设置消息ID到 OrderDto，用于幂等性控制
            orderDto.setMessageId(messageId);
            
            log.info("第一个消息消费者监听，处理消息：{}, messageId: {}", orderDto, messageId);

            // 处理订单保存（通过Service调用，事务会生效，包含幂等性检查）
            orderService.processOrderMessage(orderDto);

            // 消息确认
            channel.basicAck(deliveryTag, false);
            log.info("消息已确认，订单处理成功，messageId: {}", messageId);
        } catch (IllegalStateException e) {
            // 幂等性异常：消息已处理过或正在处理中，直接确认消息（不重新入队）
            log.warn("消息已处理过或正在处理中，跳过处理，messageId: {}, 错误: {}", messageId, e.getMessage());
            try {
                channel.basicAck(deliveryTag, false);
            } catch (Exception ex) {
                log.error("确认已处理消息时发生异常", ex);
            }
        } catch (Exception e) {
            log.error("处理订单消息时发生异常，消息将重新入队，messageId: {}", messageId, e);
            try {
                channel.basicNack(deliveryTag, false, true);
            } catch (Exception ex) {
                log.error("拒绝消息时发生异常", ex);
            }
        }
    }
}
