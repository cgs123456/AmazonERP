package com.amz.config;

import com.amz.constant.MqConstant;
import org.springframework.amqp.core.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

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
    public Queue attentionNoticeQueue() {
        return QueueBuilder
                .durable(MqConstant.ATTENTION_NOTICE_QUEUE)
                .build();
    }

    @Bean
    public Queue likeNoticeQueue() {
        return QueueBuilder
                .durable(MqConstant.LIKE_NOTICE_QUEUE)
                .build();
    }

    @Bean
    public Queue collectionNoticeQueue() {
        return QueueBuilder
                .durable(MqConstant.COLLECTION_NOTICE_QUEUE)
                .build();
    }

    @Bean
    public Binding loginBinding() {
        return BindingBuilder
                .bind(loginNoticeQueue())
                .to(messageNoticeExchange())
                .with(MqConstant.LOGIN_KEY);
    }

    @Bean
    public Binding attentionNoticeBinding() {
        return BindingBuilder
                .bind(attentionNoticeQueue())
                .to(messageNoticeExchange())
                .with(MqConstant.ATTENTION_KEY);
    }

    @Bean
    public Binding likeNoticeBinding() {
        return BindingBuilder
                .bind(likeNoticeQueue())
                .to(messageNoticeExchange())
                .with(MqConstant.LIKE_KEY);
    }

    @Bean
    public Binding collectionNoticeBinding() {
        return BindingBuilder
                .bind(collectionNoticeQueue())
                .to(messageNoticeExchange())
                .with(MqConstant.COLLECTION_KEY);
    }
}
