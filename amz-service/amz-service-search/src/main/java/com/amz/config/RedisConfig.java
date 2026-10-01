package com.amz.config;

import com.amz.redis.RedisTemplates;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;

/**
 * redis 配置类。
 * <p>
 * 序列化配方不在这里写：4 个服务共用 amz-common 的 {@link RedisTemplates}，
 * 但 bean 仍由各服务自己声明 —— amz-common 在扫描根包 com.amz 下，
 * 把它收成一份公共 @Configuration 会给网关和另外两个刻意沿用 Boot 默认模板的服务
 * 凭空加/改 bean，换 value 序列化器还会让既有缓存读不出来。
 */
@Configuration
public class RedisConfig {

    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory connectionFactory) {
        return RedisTemplates.jsonStringValues(connectionFactory);
    }
}
