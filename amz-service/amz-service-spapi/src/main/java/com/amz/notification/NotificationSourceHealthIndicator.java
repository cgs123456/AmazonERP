package com.amz.notification;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 通知事件源健康探针（{@code /actuator/health} 的 {@code notificationSource} 项）。
 * <p>
 * 这个探针要回答的问题不是「进程活着吗」，而是「我们是否真的在消费 Amazon 的通知」。
 * 通知链路最危险的失效形态是<b>静默失效</b>：队列权限被回收、应用被取消授权、
 * 订阅被删，服务依然正常启动、接口依然 200，只是「今天没有通知」。
 * 没有这个探针，这类故障只能等运营发现订单不更新才知道。
 * <p>
 * 状态判定的刻意取舍：
 * <ul>
 *   <li><b>DOWN 只留给配置错误</b>：{@code sqs} 源缺 queue-url / region、
 *       生产 profile 用合成事件源。这些是「启动就该拦下」的问题，
 *       探针再报一次是为了覆盖配置被热更新破坏的情况。</li>
 *   <li><b>可恢复的异常只降级不判死</b>：从未成功拉取、拉取连续失败、
 *       存在未解析订阅积压，都表现为 {@code UP + degraded=true}。
 *       本模块同时承载 HTTP 接口，把 readiness 打成 DOWN 会让 K8s 摘掉整个服务的流量，
 *       故障半径从「通知不消费」放大成「ERP 不可用」——这是典型的自伤式告警。</li>
 * </ul>
 * 因此「降级」必须靠 Prometheus 的 {@code amz_spapi_notification_*} 指标告警，
 * 而不是靠 readiness 探针重启 Pod。
 */
@Component
public class NotificationSourceHealthIndicator implements HealthIndicator {

    /** 健康详情键：事件源类型。 */
    public static final String DETAIL_SOURCE = "source";
    /** 健康详情键：链路是否启用。 */
    public static final String DETAIL_ENABLED = "enabled";
    /** 健康详情键：是否降级。 */
    public static final String DETAIL_DEGRADED = "degraded";
    /** 健康详情键：降级原因。 */
    public static final String DETAIL_REASON = "reason";

    private final NotificationProperties properties;
    private final Environment environment;
    private final AtomicLong lastSuccessAtMillis = new AtomicLong(0L);
    private final AtomicLong lastFailureAtMillis = new AtomicLong(0L);
    private final AtomicLong consecutiveFailures = new AtomicLong(0L);
    private final AtomicLong lastPolledCount = new AtomicLong(0L);
    private final AtomicLong unresolvedBacklog = new AtomicLong(0L);
    private final AtomicReference<String> lastError = new AtomicReference<>("");

    public NotificationSourceHealthIndicator(NotificationProperties properties, Environment environment) {
        this.properties = Objects.requireNonNull(properties, "NotificationProperties");
        this.environment = Objects.requireNonNull(environment, "Environment");
    }

    /** 一次成功的拉取。 */
    public void recordPollSuccess(int polledCount) {
        lastSuccessAtMillis.set(System.currentTimeMillis());
        lastPolledCount.set(Math.max(0, polledCount));
        consecutiveFailures.set(0L);
        lastError.set("");
    }

    /**
     * 一次失败的拉取。
     *
     * @param errorSummary 只传异常类名或固定错误码，禁止传入异常 message 或报文内容
     */
    public void recordPollFailure(String errorSummary) {
        lastFailureAtMillis.set(System.currentTimeMillis());
        consecutiveFailures.incrementAndGet();
        lastError.set(errorSummary == null ? "" : errorSummary);
    }

    /** 更新未解析订阅积压数，用于暴露「有事件但归不了店铺」。 */
    public void recordUnresolvedBacklog(long count) {
        unresolvedBacklog.set(Math.max(0L, count));
    }

    @Override
    public Health health() {
        String source = NotificationStartupCheck.normalizeSource(properties);
        boolean enabled = properties.isEnabled();
        if (!enabled) {
            return Health.up()
                    .withDetail(DETAIL_SOURCE, source)
                    .withDetail(DETAIL_ENABLED, false)
                    .withDetail("note", "通知链路未启用；本探针为 UP 不代表已接通 Amazon")
                    .build();
        }
        if ("sqs".equals(source) && (isBlank(properties.getQueueUrl()) || isBlank(properties.getRegion()))) {
            return Health.down()
                    .withDetail(DETAIL_SOURCE, source)
                    .withDetail(DETAIL_ENABLED, true)
                    .withDetail(DETAIL_REASON, "SQS 事件源缺少 queue-url / region，无法消费通知")
                    .build();
        }
        if ("mock".equals(source)) {
            if (isProdProfile()) {
                return Health.down()
                        .withDetail(DETAIL_SOURCE, source)
                        .withDetail(DETAIL_ENABLED, true)
                        .withDetail(DETAIL_REASON, "生产 profile 禁止使用合成事件源")
                        .build();
            }
            return Health.up()
                    .withDetail(DETAIL_SOURCE, source)
                    .withDetail(DETAIL_ENABLED, true)
                    .withDetail(DETAIL_DEGRADED, true)
                    .withDetail(DETAIL_REASON, "合成事件源：链路可跑通但不是真实 Amazon 数据，禁止据此判断已接通")
                    .withDetail("lastPolledCount", lastPolledCount.get())
                    .build();
        }

        long successAt = lastSuccessAtMillis.get();
        long failures = consecutiveFailures.get();
        long backlog = unresolvedBacklog.get();
        Health.Builder builder = Health.up()
                .withDetail(DETAIL_SOURCE, source)
                .withDetail(DETAIL_ENABLED, true)
                .withDetail("consecutiveFailures", failures)
                .withDetail("lastPolledCount", lastPolledCount.get())
                .withDetail("unresolvedBacklog", backlog);
        // Health.Builder 不接受 null 值，未发生的时刻用 "never" 表达而不是省略键：
        // 省略键会让「从未成功」和「字段忘了填」在排查时无法区分。
        builder.withDetail("lastSuccessAt", successAt == 0 ? "never" : Instant.ofEpochMilli(successAt).toString());
        long failureAt = lastFailureAtMillis.get();
        builder.withDetail("lastFailureAt", failureAt == 0 ? "never" : Instant.ofEpochMilli(failureAt).toString());
        if (failures > 0) {
            builder.withDetail(DETAIL_DEGRADED, true)
                    .withDetail(DETAIL_REASON, "事件源连续拉取失败 " + failures + " 次：" + lastError.get());
        } else if (successAt == 0) {
            builder.withDetail(DETAIL_DEGRADED, true)
                    .withDetail(DETAIL_REASON, "启用至今尚未成功拉取过任何通知，请检查队列权限与订阅");
        } else if (backlog > 0) {
            builder.withDetail(DETAIL_DEGRADED, true)
                    .withDetail(DETAIL_REASON, "存在 " + backlog + " 条未解析订阅的事件，订阅映射需要补齐");
        }
        return builder.build();
    }

    private boolean isProdProfile() {
        String[] active = environment.getActiveProfiles();
        if (active != null) {
            for (String profile : active) {
                if ("prod".equalsIgnoreCase(profile)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
