package com.amz.config;

import com.amz.constant.MqConstant;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import jakarta.annotation.PostConstruct;

@Slf4j
@Configuration
public class MqConfig implements RabbitTemplate.ConfirmCallback, RabbitTemplate.ReturnsCallback {

    @Autowired
    private RabbitTemplate rabbitTemplate;


    /**
     * 构造方法执行结束后立刻执行此方法。即初始化逻辑。
     */
    @PostConstruct
    public void init(){
        // 设置RabbitTemplate中的回调逻辑
        this.rabbitTemplate.setConfirmCallback(this);
        this.rabbitTemplate.setReturnsCallback(this);
    }

    /**
     * 消息路由失败回调逻辑
     * @param returned 路由失败的消息
     */
    @Override
    public void returnedMessage(ReturnedMessage returned) {
        log.error("交换器={} 路由键={} 路由失败编码={} 路由失败描述={} 消息={}",
                returned.getExchange(), returned.getRoutingKey(),
                returned.getReplyCode(), returned.getReplyText(), returned.getMessage());
    }

    /**
     * 交换器到达队列失败回调逻辑
     * @param correlationData 消息唯一标记
     * @param ack 是否确认
     * @param cause 不能到达交换器（即ack为false）的具体原因
     */
    @Override
    public void confirm(CorrelationData correlationData, boolean ack, String cause) {
        if (ack) {
            log.info("消息确认到达交换器 correlationData={}", correlationData);
        } else {
            log.error("消息未能到达交换器 correlationData={} cause={}", correlationData, cause);
        }
    }

    // 声明交换机
    @Bean
    public DirectExchange saveOrderExchange() {
        return new DirectExchange(MqConstant.SAVE_ORDER_EXCHANGE);
    }

    // 声明队列（durable，绑定 DLX 防止毒消息无限重投）
    @Bean
    public Queue saveOrderQueue() {
        return QueueBuilder.durable(MqConstant.SAVE_ORDER_QUEUE)
                .withArgument("x-dead-letter-exchange", MqConstant.SAVE_ORDER_DLX_EXCHANGE)
                .withArgument("x-dead-letter-routing-key", MqConstant.SAVE_ORDER_DLQ_ROUTING_KEY)
                .build();
    }

    // 使用routing key将队列绑定到交换机
    @Bean
    public Binding binding(Queue saveOrderQueue, DirectExchange saveOrderExchange) {
        return BindingBuilder.bind(saveOrderQueue).to(saveOrderExchange).with("");
    }

    // ===== 死信队列 =====
    @Bean
    public DirectExchange saveOrderDlxExchange() {
        return new DirectExchange(MqConstant.SAVE_ORDER_DLX_EXCHANGE, true, false);
    }

    @Bean
    public Queue saveOrderDlqQueue() {
        return new Queue(MqConstant.SAVE_ORDER_DLQ_QUEUE, true);
    }

    @Bean
    public Binding saveOrderDlqBinding(Queue saveOrderDlqQueue, DirectExchange saveOrderDlxExchange) {
        return BindingBuilder.bind(saveOrderDlqQueue).to(saveOrderDlxExchange)
                .with(MqConstant.SAVE_ORDER_DLQ_ROUTING_KEY);
    }

}