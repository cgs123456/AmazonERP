package com.amz.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
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
}