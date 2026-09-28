package com.amz.notification;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 事件源装配：{@code mock} 与 {@code sqs} 共用同一个 {@link NotificationEventSource} 类型。
 * <p>
 * 为什么不在 {@code @Bean} 上直接写
 * {@code @ConditionalOnProperty(name="source", havingValue="sqs")}：
 * 该注解是<b>大小写敏感</b>的精确匹配，而 {@code SPAPI_NOTIFICATIONS_SOURCE=SQS}
 * 这类常见写法会让两个分支都不生效，结果是没有事件源 Bean —— 启动失败，
 * 且报错信息指向「找不到 Bean」，离真正的原因（大小写）很远。
 * 这里统一走 {@link NotificationStartupCheck#normalizeSource(NotificationProperties)}
 * 归一化取值，与启动校验、健康探针保持同一份口径。
 * <p>
 * 事件源只在 {@code spapi.notifications.enabled=true} 时创建：
 * 关闭时不建客户端、不占线程、不产生对 AWS 的任何调用。
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "spapi.notifications", name = "enabled", havingValue = "true")
public class NotificationEventSourceConfig {

    /**
     * 按配置创建事件源。
     *
     * @throws IllegalStateException source 既不是 mock 也不是 sqs（启动期拒绝，fail-closed）
     */
    @Bean
    public NotificationEventSource notificationEventSource(ObjectMapper objectMapper,
                                                          NotificationProperties properties,
                                                          NotificationMetrics metrics) {
        String source = NotificationStartupCheck.normalizeSource(properties);
        return switch (source) {
            case NotificationStartupCheck.SOURCE_SQS ->
                    new SqsNotificationEventSource(properties, metrics);
            case NotificationStartupCheck.SOURCE_MOCK ->
                    new MockNotificationEventSource(objectMapper, properties.getMockScenario());
            default -> throw new IllegalStateException(
                    "通知入站启动校验失败：spapi.notifications.source=" + properties.getSource()
                            + " 非法，只允许 mock 或 sqs（大小写不敏感）。");
        };
    }
}
