package com.amz.voucher;

import com.amz.constant.MqConstant;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 凭证生成 RabbitMQ 配置（B1）。
 * <p>
 * order 服务订单落库后发消息，本服务消费并生成会计凭证（替代同步 Feign，
 * Seata 全局事务不再跨服务持有锁）。主队列绑定 DLX，消费失败
 * （basicNack requeue=false）的消息转入死信队列，避免毒消息无限重投。
 */
@Configuration
public class FinanceVoucherMqConfig {

    public static final String VOUCHER_QUEUE = MqConstant.VOUCHER_QUEUE;
    public static final String VOUCHER_EXCHANGE = MqConstant.VOUCHER_EXCHANGE;
    public static final String VOUCHER_ROUTING_KEY = MqConstant.VOUCHER_ROUTING_KEY;

    /**
     * 主队列：durable，绑定 DLX，失败消息按 DLQ 路由键转入死信队列。
     */
    @Bean
    public Queue voucherQueue() {
        return QueueBuilder.durable(VOUCHER_QUEUE)
                .withArgument("x-dead-letter-exchange", MqConstant.VOUCHER_DLX_EXCHANGE)
                .withArgument("x-dead-letter-routing-key", MqConstant.VOUCHER_DLQ_ROUTING_KEY)
                .build();
    }

    @Bean
    public DirectExchange voucherExchange() {
        return new DirectExchange(VOUCHER_EXCHANGE, true, false);
    }

    @Bean
    public Binding voucherBinding(Queue voucherQueue, DirectExchange voucherExchange) {
        return BindingBuilder.bind(voucherQueue).to(voucherExchange).with(VOUCHER_ROUTING_KEY);
    }

    /**
     * 死信交换机（DirectExchange）：失败消息按 DLQ 路由键投递到死信队列。
     */
    @Bean
    public DirectExchange voucherDlxExchange() {
        return new DirectExchange(MqConstant.VOUCHER_DLX_EXCHANGE, true, false);
    }

    /**
     * 死信队列：durable，存放消费失败的凭证消息供人工排查/重放。
     */
    @Bean
    public Queue voucherDlqQueue() {
        return new Queue(MqConstant.VOUCHER_DLQ_QUEUE, true);
    }

    @Bean
    public Binding voucherDlqBinding(Queue voucherDlqQueue, DirectExchange voucherDlxExchange) {
        return BindingBuilder.bind(voucherDlqQueue).to(voucherDlxExchange)
                .with(MqConstant.VOUCHER_DLQ_ROUTING_KEY);
    }
}
