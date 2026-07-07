package com.amz.profit;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 利润核算 RabbitMQ 配置
 */
@Configuration
public class ProfitMQConfig {

    public static final String PROFIT_QUEUE = "amz.order.profit.queue";
    public static final String PROFIT_EXCHANGE = "amz.order.profit.exchange";
    public static final String PROFIT_ROUTING_KEY = "amz.order.profit";

    @Bean
    public Queue profitQueue() {
        // durable = true
        return new Queue(PROFIT_QUEUE, true);
    }

    @Bean
    public DirectExchange profitExchange() {
        return new DirectExchange(PROFIT_EXCHANGE, true, false);
    }

    @Bean
    public Binding profitBinding(Queue profitQueue, DirectExchange profitExchange) {
        return BindingBuilder.bind(profitQueue).to(profitExchange).with(PROFIT_ROUTING_KEY);
    }
}
