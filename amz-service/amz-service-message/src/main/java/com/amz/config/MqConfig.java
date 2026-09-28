package com.amz.config;

import com.amz.constant.MqConstant;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.ExchangeBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RabbitMQ 配置。
 * <p>
 * 已清理社交场景的关注/点赞/收藏队列绑定，
 * 保留登录通知队列。
 */
@Configuration
public class MqConfig {

    @Bean
    public TopicExchange messageNoticeExchange() {
        return ExchangeBuilder
                .topicExchange(MqConstant.MESSAGE_NOTICE_EXCHANGE)
                .durable(true)
                .build();
    }

    @Bean
    public Queue loginNoticeQueue() {
        return QueueBuilder
                .durable(MqConstant.LOGIN_NOTICE_QUEUE)
                .build();
    }

    @Bean
    public Binding loginBinding() {
        return BindingBuilder
                .bind(loginNoticeQueue())
                .to(messageNoticeExchange())
                .with(MqConstant.LOGIN_KEY);
    }
}
