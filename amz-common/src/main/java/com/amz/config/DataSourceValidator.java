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
 * 生产 profile 下发现空密码或占位默认值会直接拒绝启动，避免服务带着错误配置进入运行期；
 * 非生产 profile 仅记录 warn，便于本地开发和离线演示。未配置的中间件属性会被跳过，
 * 因此公共模块不会因为服务不使用的中间件而误拒绝启动。
 */
@Slf4j
@Component
public class DataSourceValidator {

    /** 已知的占位默认值前缀（出现即视为未配置真实密码） */
    private static final String[] PLACEHOLDER_PREFIXES = {
            "your_", "your-", "change_me", "change-me", "changeme",
            "replace_me", "replace-me", "placeholder"
    };

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

        boolean prodProfile = isProdProfile();
        for (Map.Entry<String, String> entry : passwordProperties.entrySet()) {
            String propertyPath = entry.getKey();
            String label = entry.getValue();
            // environment.getProperty 对未配置的属性返回 null，据此跳过不使用的中间件
            String value = environment.getProperty(propertyPath);
            if (value == null) {
                continue;
            }
            if (value.isBlank()) {
                String message = String.format(
                        "[密码校验] %s 密码为空（属性 %s），请通过环境变量、Secret 或配置中心注入真实密码。",
                        label, propertyPath);
                rejectOrWarn(prodProfile, message);
            } else if (isPlaceholder(value)) {
                String message = String.format(
                        "[密码校验] %s 密码仍为占位默认值（属性 %s），请通过环境变量、Secret 或配置中心注入真实密码。",
                        label, propertyPath);
                rejectOrWarn(prodProfile, message);
            }
        }
    }

    private void rejectOrWarn(boolean prodProfile, String message) {
        if (prodProfile) {
            throw new IllegalStateException(message);
        }
        log.warn(message);
    }

    private boolean isProdProfile() {
        for (String profile : environment.getActiveProfiles()) {
            if ("prod".equalsIgnoreCase(profile)) {
                return true;
            }
        }
        return false;
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
