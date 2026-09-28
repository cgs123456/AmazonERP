package com.amz.notification;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 通知链路统一指标出口，前缀 {@code amz.spapi.notification}。
 * <p>
 * 为什么必须单独收口而不是各组件直接拿 {@code MeterRegistry}：
 * <ul>
 *   <li><b>标签基数</b>：通知类型来自 Amazon 报文，属于外部输入。若放任各组件直接
 *       {@code tag("type", 任意字符串)}，一个异常报文就能把 Prometheus 时间序列打爆。
 *       这里对标签统一做空值兜底与长度截断。</li>
 *   <li><b>口径一致</b>：{@code received / duplicate / invalid / unresolved_subscription /
 *       unsupported / processed / retry_scheduled / dlq / payload_too_large /
 *       sqs_delete_failed} 的名字与含义只有一处定义，改名不会漏改。</li>
 * </ul>
 * <p>
 * 只暴露计数与耗时，<b>不暴露任何业务字段值</b>：订单通知含买家 PII，
 * 一旦把 amazonOrderId / buyerName 塞进标签，就等于把 PII 写进监控系统并长期留存。
 */
@Component
public class NotificationMetrics {

    /** 指标名前缀。Prometheus 抓取时点号会被转成下划线。 */
    public static final String PREFIX = "amz.spapi.notification";

    /** 通知类型标签键。 */
    public static final String TAG_TYPE = "type";
    /** 终态标签键（DLQ / UNSUPPORTED / UNRESOLVED_SUBSCRIPTION / INVALID）。 */
    public static final String TAG_STATUS = "status";
    /** 漂移类型标签键。 */
    public static final String TAG_KIND = "kind";

    /** 标签缺失或非法时的统一取值，避免出现空标签。 */
    public static final String UNKNOWN = "unknown";

    /** 标签最大长度，防止外部输入撑爆时间序列基数。 */
    private static final int MAX_TAG_LENGTH = 64;

    private final MeterRegistry registry;
    private final AtomicLong inboxLagSeconds = new AtomicLong(0L);

    public NotificationMetrics(MeterRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "MeterRegistry 不能为 null");
        Gauge.builder(PREFIX + ".inbox.lag.seconds", inboxLagSeconds, AtomicLong::get)
                .description("Inbox 中最老待处理事件的滞留秒数，用于发现消费停滞")
                .register(registry);
    }

    /** 收到并成功落库一条新事件。 */
    public void received(String notificationType) {
        counter("received", TAG_TYPE, tag(notificationType)).increment();
    }

    /** 重复投递且哈希一致：SQS standard 队列的预期内重投。 */
    public void duplicateSameHash() {
        counter("duplicate").increment();
    }

    /** 重复投递但哈希不同：Amazon 侧可能重发了不同内容，必须告警。 */
    public void duplicateDiffHash() {
        counter("duplicate.diff").increment();
    }

    /** 载荷超过 payload-max-bytes：留证不存正文。 */
    public void payloadTooLarge() {
        counter("payload.too.large").increment();
    }

    /** 事件本身不可用（缺字段 / 非法 JSON）。 */
    public void invalid(String errorCode) {
        counter("invalid", "errorCode", tag(errorCode)).increment();
    }

    /** subscriptionId 反查不到店铺：隔离等待补映射，不删消息不进 DLQ。 */
    public void unresolvedSubscription() {
        counter("unresolved.subscription").increment();
    }

    /** 通知类型尚未实现处理器：事件没坏，补上 Handler 后可批量重放。 */
    public void unsupported(String notificationType) {
        counter("unsupported", TAG_TYPE, tag(notificationType)).increment();
    }

    /** 处理成功：同时记录耗时分布。 */
    public void processed(String notificationType, long durationNanos) {
        counter("processed", TAG_TYPE, tag(notificationType)).increment();
        Timer.builder(PREFIX + ".handler.duration")
                .tag(TAG_TYPE, tag(notificationType))
                .description("单个通知 Handler 的处理耗时")
                .register(registry)
                .record(java.time.Duration.ofNanos(Math.max(0L, durationNanos)));
    }

    /** 处理失败并安排退避重试。 */
    public void retryScheduled() {
        counter("retry.scheduled").increment();
    }

    /** 进入终态（DLQ 或其细分终态）。 */
    public void dlq(String terminalStatus) {
        counter("dlq", TAG_STATUS, tag(terminalStatus)).increment();
    }

    /** 删除 SQS 消息失败：消息会重投，靠 Inbox 唯一键去重兜底。 */
    public void sqsDeleteFailed() {
        counter("sqs.delete.failed").increment();
    }

    /** 订阅对账发现漂移。 */
    public void subscriptionDrift(String kind) {
        counter("subscription.drift", TAG_KIND, tag(kind)).increment();
    }

    /** 更新 Inbox 滞留 Gauge。 */
    public void recordInboxLagSeconds(long seconds) {
        inboxLagSeconds.set(Math.max(0L, seconds));
    }

    /** 当前 Inbox 滞留秒数（Gauge 的观测源）。 */
    public long inboxLagSeconds() {
        return inboxLagSeconds.get();
    }

    /**
     * 取（必要时创建）一个 Counter，便于测试直接读 {@code count()}。
     *
     * @param suffix 指标名后缀，不含前缀
     * @param tags   标签键值对，必须为偶数个；奇数时最后一个被忽略
     */
    public Counter counter(String suffix, String... tags) {
        Counter.Builder builder = Counter.builder(PREFIX + "." + suffix);
        applyTags(builder, tags);
        return builder.register(registry);
    }

    /** 标签归一化：空值兜底、去空白、截断长度。 */
    public static String tag(String raw) {
        if (raw == null) {
            return UNKNOWN;
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            return UNKNOWN;
        }
        return trimmed.length() <= MAX_TAG_LENGTH ? trimmed : trimmed.substring(0, MAX_TAG_LENGTH);
    }

    private static void applyTags(Counter.Builder builder, String... tags) {
        if (tags == null) {
            return;
        }
        for (int i = 0; i + 1 < tags.length; i += 2) {
            builder.tag(tags[i], tag(tags[i + 1]));
        }
    }
}
