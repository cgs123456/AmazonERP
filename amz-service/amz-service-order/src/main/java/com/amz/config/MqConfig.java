package com.amz.config;

import com.amz.constant.MqConstant;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import jakarta.annotation.PostConstruct;

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
        System.out.println("交换器 : " + returned.getExchange());
        System.out.println("路由键 : " + returned.getRoutingKey());
        System.out.println("路由失败编码 : " + returned.getReplyCode());
        System.out.println("路由失败描述 : " + returned.getReplyText());
        System.out.println("消息 : " + returned.getMessage());
    }

    /**
     * 交换器到达队列失败回调逻辑
     * @param correlationData 消息唯一标记
     * @param ack 是否确认
     * @param cause 不能到达交换器（即ack为false）的具体原因
     */
    @Override
    public void confirm(CorrelationData correlationData, boolean ack, String cause) {
        System.out.println("消息唯一标记 : " + correlationData);
        System.out.println("是否确认到达交换器 : " + ack);
        System.out.println("不能到达交换器的原因 : " + cause);
    }

    // 声明交换机
    @Bean
    public DirectExchange saveOrderExchange() {
        return new DirectExchange(MqConstant.SAVE_ORDER_EXCHANGE);
    }

    // 声明队列
    @Bean
    public Queue saveOrderQueue() {
        return new Queue(MqConstant.SAVE_ORDER_QUEUE);
    }

    // 使用routing key将队列绑定到交换机
    @Bean
    public Binding binding(Queue saveOrderQueue, DirectExchange saveOrderExchange) {
        return BindingBuilder.bind(saveOrderQueue).to(saveOrderExchange).with("");
    }

}
