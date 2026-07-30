package com.amz.config;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 数据源/中间件密码启动校验。
 * <p>
 * 生产环境若未通过环境变量注入密码，配置占位默认值为空，连接会快速失败暴露问题。
 * 此组件在启动阶段对关键密码做非空、非占位校验，仅输出 warn 日志，不抛异常阻断启动
 * （避免 amz-common 作为公共模块被各服务引入时因缺少某类中间件配置而拒绝启动）。
 */
@Slf4j
@Component
public class DataSourceValidator {

    /** 已知的占位默认值前缀（出现即视为未配置真实密码） */
    private static final String[] PLACEHOLDER_PREFIXES = {"your_", "your-", "changeme", "placeholder"};

    private final Environment environment;

    public DataSourceValidator(Environment environment) {
        this.environment = environment;
    }

    @PostConstruct
    public void validate() {
        // 使用 LinkedHashMap 保持校验顺序；key 为配置属性路径，value 为人类可读标签
        Map<String, String> passwordProperties = new LinkedHashMap<>();
        passwordProperties.put("spring.datasource.password", "DB");
        passwordProperties.put("spring.datasource.dynamic.datasource.master.password", "DB(master)");
        passwordProperties.put("spring.datasource.dynamic.datasource.slave.password", "DB(slave)");
        passwordProperties.put("spring.data.redis.password", "Redis");
        passwordProperties.put("spring.rabbitmq.password", "RabbitMQ");
        passwordProperties.put("spring.data.mongodb.password", "MongoDB");

        for (Map.Entry<String, String> entry : passwordProperties.entrySet()) {
            String propertyPath = entry.getKey();
            String label = entry.getValue();
            // environment.getProperty 对未配置的属性返回 null，据此跳过不使用的中间件
            String value = environment.getProperty(propertyPath);
            if (value == null) {
                continue;
            }
            if (value.isEmpty()) {
                log.warn("[密码校验] {} 密码为空（属性 {}），请确认已通过环境变量注入；生产环境连接将失败暴露此问题。",
                        label, propertyPath);
            } else if (isPlaceholder(value)) {
                log.warn("[密码校验] {} 密码仍为占位默认值 '{}'（属性 {}），请通过环境变量注入真实密码。",
                        label, value, propertyPath);
            }
        }
    }

    private boolean isPlaceholder(String value) {
        String lower = value.toLowerCase();
        for (String prefix : PLACEHOLDER_PREFIXES) {
            if (lower.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }
}
