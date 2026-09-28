package com.amz.scheduler;

import com.amz.mapper.NotificationInboxMapper;
import com.amz.model.NotificationInboxEntity;
import com.amz.notification.InvalidNotificationException;
import com.amz.notification.NotificationEnvelope;
import com.amz.notification.NotificationEventSource;
import com.amz.notification.NotificationInboxService;
import com.amz.notification.NotificationMetrics;
import com.amz.notification.NotificationPayloadValidator;
import com.amz.notification.NotificationProperties;
import com.amz.notification.NotificationShopBinding;
import com.amz.notification.NotificationSourceHealthIndicator;
import com.amz.notification.NotificationSubscriptionRegistry;
import com.amz.notification.ValidatedNotification;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 通知入站编排调度：拉取 -> 校验 -> 反查店铺 -> 落 Inbox -> 【落库成功才】确认消息。
 * <p>
 * 顺序是这个类的<b>唯一契约</b>，任何重构都不能调换：
 * <pre>
 *   source.poll -> validator.validate -> registry.resolve -> inbox.ingest -> source.acknowledge
 * </pre>
 * 「先删消息再落库」是这条链路上最贵的一种错误：Amazon 已经投递完成，
 * 我们删掉消息后任何一步失败都不可恢复——没有消息、没有记录、没有告警。
 * 反过来「落库成功但删消息失败」会怎样？消息在可见性超时后重投，
 * 重投被 {@code uk_notification_id} 去重吸收，最多多一次空转。
 * 两种错误的代价差着几个数量级，所以这里只保留后者。
 * <p>
 * 未确认（不 ack）的情形与理由：
 * <ul>
 *   <li><b>结构非法</b>（{@link InvalidNotificationException}）：重试只会拿到同一条坏消息，
 *       进展为零；不删除则消息最终由队列 RedrivePolicy 进入 SQS 侧 DLQ，
 *       原始报文仍然完整可查。删除等于销毁证据。</li>
 *   <li><b>载荷超限</b>（{@link NotificationInboxService.IngestResult#TOO_LARGE}）：
 *       Inbox 只留了一条 INVALID 存根，正文已按 {@code payload-max-bytes} 丢弃。
 *       不删除是为了让运维能从 SQS DLQ 取回原始报文核对为什么超限。</li>
 *   <li><b>落库抛出运行时异常</b>：DB 抖动 / 连接池耗尽，这类错误重试是有意义的，
 *       必须保留消息。</li>
 * </ul>
 * 因此生产部署<b>必须</b>给队列配置 RedrivePolicy（maxReceiveCount），
 * 否则上述消息会无限重投并持续占据每轮 10 条配额。
 * <p>
 * 归属未解析（{@code UNRESOLVED_SUBSCRIPTION}）时事件<b>已经落库</b>，
 * 所以照常 ack：它不会被丢，只是被隔离在 Inbox 里等补映射后重放。
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "spapi.notifications", name = "enabled", havingValue = "true")
public class NotificationIngestionScheduler {

    /** 每多少轮查一次未解析订阅积压：COUNT 是慢查询，不必每轮都付代价。 */
    public static final int BACKLOG_CHECK_EVERY_RUNS = 20;

    private final NotificationEventSource source;
    private final NotificationPayloadValidator validator;
    private final NotificationSubscriptionRegistry registry;
    private final NotificationInboxService inboxService;
    private final NotificationInboxMapper inboxMapper;
    private final NotificationProperties properties;
    private final NotificationMetrics metrics;
    private final NotificationSourceHealthIndicator health;
    private final AtomicLong runs = new AtomicLong(0L);

    public NotificationIngestionScheduler(NotificationEventSource source,
                                          NotificationPayloadValidator validator,
                                          NotificationSubscriptionRegistry registry,
                                          NotificationInboxService inboxService,
                                          NotificationInboxMapper inboxMapper,
                                          NotificationProperties properties,
                                          NotificationMetrics metrics,
                                          NotificationSourceHealthIndicator health) {
        this.source = source;
        this.validator = validator;
        this.registry = registry;
        this.inboxService = inboxService;
        this.inboxMapper = inboxMapper;
        this.properties = properties;
        this.metrics = metrics;
        this.health = health;
    }

    /** 定时入口：任何异常都不能让调度线程死掉，否则通知会静默停止。 */
    @Scheduled(fixedDelayString = "${spapi.notifications.poll-interval-ms:5000}")
    public void run() {
        try {
            pollOnce();
        } catch (RuntimeException | Error e) {
            // 只记类名不记 message：SQS 异常 message 可能带队列 URL 与请求 ID，
            // 属于基础设施信息，不该进应用日志。
            health.recordPollFailure(e.getClass().getSimpleName());
            log.error("[NotificationIngestion] 本轮入站执行失败，等待下轮重试：error={}",
                    e.getClass().getSimpleName());
        }
    }

    /**
     * 执行一轮入站。
     *
     * @return 本轮已安全持久化（含重复吸收）的事件数
     */
    public int pollOnce() {
        List<NotificationEnvelope> batch = source.poll(properties.getMaxMessages());
        List<NotificationEnvelope> safe = batch == null ? List.of() : batch;
        health.recordPollSuccess(safe.size());
        if (safe.isEmpty()) {
            maybeRefreshUnresolvedBacklog();
            return 0;
        }
        List<String> ackHandles = new ArrayList<>(safe.size());
        int ingested = 0;
        for (NotificationEnvelope envelope : safe) {
            switch (ingest(envelope)) {
                case ACK -> {
                    ackHandles.add(envelope.receiptHandle());
                    ingested++;
                }
                case SKIP -> {
                }
            }
        }
        if (!ackHandles.isEmpty()) {
            source.acknowledge(ackHandles);
        }
        maybeRefreshUnresolvedBacklog();
        return ingested;
    }

    /** 单条事件的处理结果：ACK 表示已安全持久化，可以删除源消息。 */
    enum Outcome {ACK, SKIP}

    private Outcome ingest(NotificationEnvelope envelope) {
        ValidatedNotification validated;
        try {
            validated = validator.validate(envelope.rawJson(), properties.getPayloadMaxBytes());
        } catch (InvalidNotificationException e) {
            metrics.invalid(e.getErrorCode());
            log.warn("[NotificationIngestion] 通知结构非法，保留消息等待队列 DLQ：errorCode={}, "
                            + "receiptHandlePrefix={}", e.getErrorCode(), prefix(envelope.receiptHandle()));
            return Outcome.SKIP;
        }
        NotificationShopBinding binding;
        try {
            binding = registry.resolve(validated.subscriptionId()).orElse(null);
        } catch (RuntimeException e) {
            // 反查失败（DB 不可用）时绝不落库：落进 Inbox 却没 shopId 会让事件永远归不了店。
            log.error("[NotificationIngestion] 订阅映射反查失败，保留消息等下轮重投："
                            + "subscriptionId={}, error={}", validated.subscriptionId(),
                    e.getClass().getSimpleName());
            return Outcome.SKIP;
        }
        try {
            NotificationInboxService.IngestResult result = inboxService.ingest(
                    validated, envelope.rawJson(), binding, envelope.synthetic());
            if (result == NotificationInboxService.IngestResult.TOO_LARGE) {
                // 已留 INVALID 存根，但正文被丢弃：不删消息，让运维能从 SQS DLQ 取回原始报文。
                log.error("[NotificationIngestion] 载荷超限，已留存根但保留消息以便回溯："
                                + "notificationId={}, type={}, bytes={}, limit={}",
                        validated.notificationId(), validated.notificationType(),
                        validated.payloadBytes(), properties.getPayloadMaxBytes());
                return Outcome.SKIP;
            }
            return Outcome.ACK;
        } catch (RuntimeException e) {
            log.error("[NotificationIngestion] 落库失败，保留消息等待重投：notificationId={}, type={}, error={}",
                    validated.notificationId(), validated.notificationType(), e.getClass().getSimpleName());
            return Outcome.SKIP;
        }
    }

    private void maybeRefreshUnresolvedBacklog() {
        if (runs.incrementAndGet() % BACKLOG_CHECK_EVERY_RUNS != 0) {
            return;
        }
        try {
            Long count = inboxMapper.selectCount(new LambdaQueryWrapper<NotificationInboxEntity>()
                    .eq(NotificationInboxEntity::getStatus,
                            com.amz.notification.NotificationInboxStatus.UNRESOLVED_SUBSCRIPTION));
            health.recordUnresolvedBacklog(count == null ? 0L : count);
        } catch (RuntimeException e) {
            log.warn("[NotificationIngestion] 统计未解析订阅积压失败：error={}", e.getClass().getSimpleName());
        }
    }

    private static String prefix(String receiptHandle) {
        if (receiptHandle == null) {
            return "";
        }
        return receiptHandle.length() <= 8 ? receiptHandle : receiptHandle.substring(0, 8);
    }
}
