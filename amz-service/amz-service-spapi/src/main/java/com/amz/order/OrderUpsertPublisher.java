package com.amz.order;

import com.amz.constant.MqConstant;
import com.google.gson.Gson;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

/**
 * 订单 upsert 消息发布器（通知链路与定时同步共用）。
 * <p>
 * 与旧实现的唯一差别，也是这里的重点：<b>发送失败必须抛异常</b>。
 * 旧 {@code OrderSyncScheduler.sendJson} 把异常 catch 住只打一行日志，
 * 调用方看到的是「同步成功」——MQ 宕机时整条链路静默丢单，
 * 界面上订单数不涨，和「真的没有新订单」完全无法区分。
 * 抛异常后：通知链路走重试 / DLQ 留证，定时同步由调用方决定降级与告警。
 */
@Component
public class OrderUpsertPublisher {

    private final RabbitTemplate rabbitTemplate;
    private final Gson gson = new Gson();

    public OrderUpsertPublisher(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    /**
     * 发布订单 upsert 消息。
     *
     * @param message 消息体
     * @throws OrderUpsertPublishException 序列化或发送失败
     */
    public void publish(OrderUpsertMessage message) {
        Map<String, Object> body;
        String json;
        try {
            body = message.toBodyMap();
            json = gson.toJson(body);
        } catch (RuntimeException e) {
            throw new OrderUpsertPublishException(
                    "订单 upsert 消息序列化失败：amazonOrderId=" + message.amazonOrderId(), e);
        }
        try {
            Message amqp = MessageBuilder
                    .withBody(json.getBytes(StandardCharsets.UTF_8))
                    .setContentType(MessageProperties.CONTENT_TYPE_TEXT_PLAIN)
                    .setMessageId(UUID.randomUUID().toString())
                    .build();
            rabbitTemplate.send(MqConstant.SAVE_ORDER_EXCHANGE, "", amqp);
        } catch (Exception e) {
            // 失败必须上抛：静默吞掉等于承认「订单可以丢」。
            throw new OrderUpsertPublishException(
                    "订单 upsert 消息发送失败：exchange=" + MqConstant.SAVE_ORDER_EXCHANGE
                            + ", amazonOrderId=" + message.amazonOrderId()
                            + ", shopId=" + message.shopId(), e);
        }
    }

    /**
     * 发送任意 JSON 消息（利润核算等场景共用同一套失败语义）。
     *
     * @throws OrderUpsertPublishException 发送失败
     */
    public void sendJson(String exchange, String routingKey, Map<String, Object> body) {
        String json;
        try {
            json = gson.toJson(body);
        } catch (RuntimeException e) {
            throw new OrderUpsertPublishException("消息序列化失败：exchange=" + exchange, e);
        }
        try {
            Message amqp = MessageBuilder
                    .withBody(json.getBytes(StandardCharsets.UTF_8))
                    .setContentType(MessageProperties.CONTENT_TYPE_TEXT_PLAIN)
                    .setMessageId(UUID.randomUUID().toString())
                    .build();
            rabbitTemplate.send(exchange, routingKey, amqp);
        } catch (Exception e) {
            throw new OrderUpsertPublishException(
                    "消息发送失败：exchange=" + exchange + ", routingKey=" + routingKey, e);
        }
    }
}