package com.amz.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;

/**
 * Seata 分布式事务自动配置。
 * 通过 seata.enabled=true 启用（默认关闭，避免本地开发/测试环境报错）。
 * 生产环境通过环境变量 SEATA_ENABLED=true 激活。
 */
@Configuration
@ConditionalOnProperty(name = "seata.enabled", havingValue = "true")
public class SeataAutoConfig {
    // io.seata.spring.boot.autoconfigure.SeataAutoConfiguration 已自动配置。
    // 本类仅作为条件开关：seata.enabled=true 时激活 Seata 自动装配。
}
