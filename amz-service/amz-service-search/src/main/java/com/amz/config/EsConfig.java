package com.amz.config;

import org.springframework.context.annotation.Configuration;

/**
 * Elasticsearch 配置类
 * Spring Boot 3 已自动配置 ElasticsearchOperations，无需手动配置
 */
@Configuration
public class EsConfig {
    // Spring Boot 3 的 spring-boot-starter-data-elasticsearch 已自动配置
    // 使用 ElasticsearchOperations 进行 ES 操作
}
