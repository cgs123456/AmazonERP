package com.amz.profit;

import com.amz.constant.MqConstant;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 利润核算 RabbitMQ 配置
 * <p>
 * 主队列 {@link #profitQueue()} 配置了死信交换机（DLX），
 * 消费失败（basicNack requeue=false）的消息会被路由到 {@link #profitDlqQueue()}，
 * 避免毒消息无限重投或被丢弃。
 */
@Configuration
public class ProfitMQConfig {

    public static final String PROFIT_QUEUE = MqConstant.PROFIT_QUEUE;
    public static final String PROFIT_EXCHANGE = MqConstant.PROFIT_EXCHANGE;
    public static final String PROFIT_ROUTING_KEY = MqConstant.PROFIT_ROUTING_KEY;

    /**
     * 主队列：durable，绑定 DLX，失败消息按 DLQ 路由键转入死信队列。
     */
    @Bean
    public Queue profitQueue() {
        return QueueBuilder.durable(PROFIT_QUEUE)
                .withArgument("x-dead-letter-exchange", MqConstant.PROFIT_DLX_EXCHANGE)
                .withArgument("x-dead-letter-routing-key", MqConstant.PROFIT_DLQ_ROUTING_KEY)
                .build();
    }

    @Bean
    public DirectExchange profitExchange() {
        return new DirectExchange(PROFIT_EXCHANGE, true, false);
    }

    @Bean
    public Binding profitBinding(Queue profitQueue, DirectExchange profitExchange) {
        return BindingBuilder.bind(profitQueue).to(profitExchange).with(PROFIT_ROUTING_KEY);
    }

    /**
     * 死信交换机（DirectExchange）：失败消息按 DLQ 路由键投递到死信队列。
     */
    @Bean
    public DirectExchange profitDlxExchange() {
        return new DirectExchange(MqConstant.PROFIT_DLX_EXCHANGE, true, false);
    }

    /**
     * 死信队列：durable，存放消费失败的利润消息供人工排查/重放。
     */
    @Bean
    public Queue profitDlqQueue() {
        return new Queue(MqConstant.PROFIT_DLQ_QUEUE, true);
    }

    @Bean
    public Binding profitDlqBinding(Queue profitDlqQueue, DirectExchange profitDlxExchange) {
        return BindingBuilder.bind(profitDlqQueue).to(profitDlxExchange).with(MqConstant.PROFIT_DLQ_ROUTING_KEY);
    }
}
