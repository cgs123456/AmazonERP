package com.amz.redis;

import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJacksonJsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializer;

/**
 * JSON 序列化的 {@link RedisTemplate} 构造配方（4 个服务里那段重复代码的唯一实现）。
 * <p>
 * 为什么只收成"配方"而不是收成一份公共 {@code @Configuration}：
 * {@code amz-common} 在扫描根包 {@code com.amz} 下，任何放进 {@code com.amz.config} 的
 * {@code @Configuration} 都会被<b>全部</b> 16 个服务扫到——包括现在并没有这个 bean 的
 * 网关（WebFlux）和明确沿用 Boot 默认模板序列化器的 procurement / spapi。
 * 那已经不是去重，而是跨服务改 Bean 拓扑：换 value 序列化器会让既有缓存读不出来。
 * 所以这里只做纯工厂，谁需要 {@code redisTemplate} 谁在自己的 {@code RedisConfig} 里调用：
 * Bean 名、返回类型、生效范围都不变，变的只是"配方从四份拷贝改成调用一次"。
 */
public final class RedisTemplates {

    private RedisTemplates() {
    }

    /**
     * key/hashKey 用 String 序列化，value/hashValue 用 JSON（Jackson 3：Boot 4 的 spring-data-redis
     * 4.x 把 Jackson 2 版标为 deprecated-for-removal，GenericJacksonJsonRedisSerializer 是官方等价替代；
     * 带类型往返契约由 RedisTemplatesTest 钉死）。
     * 不调用 {@code afterPropertiesSet()}：交给 Spring 在 Bean 初始化阶段做，
     * 与原 {@code @Bean} 方法的时序保持相同。
     */
    public static RedisTemplate<String, Object> jsonStringValues(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);

        GenericJacksonJsonRedisSerializer jsonRedisSerializer = GenericJacksonJsonRedisSerializer.builder()
                .enableUnsafeDefaultTyping()
                .build();
        template.setKeySerializer(RedisSerializer.string());
        template.setHashKeySerializer(RedisSerializer.string());
        template.setValueSerializer(jsonRedisSerializer);
        template.setHashValueSerializer(jsonRedisSerializer);
        return template;
    }
}
