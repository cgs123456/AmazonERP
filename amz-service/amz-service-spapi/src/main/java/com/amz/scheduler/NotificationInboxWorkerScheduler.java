package com.amz.scheduler;

import com.amz.notification.NotificationInboxWorker;
import com.amz.notification.NotificationProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * Inbox Worker 调度：消费已落库的事件，并回收过期租约。
 * <p>
 * 与入站调度分开成两个调度是刻意的：
 * <ul>
 *   <li><b>入站</b>的 SLA 是「别丢 Amazon 的消息」，它必须尽量快地删掉已落库的消息，
 *       否则可见性超时一到就会重投。</li>
 *   <li><b>处理</b>的 SLA 是「别把下游打爆」，订单 upsert 会打到 amz-service-order，
 *       它的速率受下游容量约束，与入站速率无关。</li>
 * </ul>
 * 合成一个循环会让「下游慢」反过来堵住入站，最终表现为 SQS 消息不断重投、
 * Inbox 堆积，但根因其实在下游。分成两个调度后，两个速率可以独立调参，
 * 指标上也能分别看出是入站慢还是处理慢。
 * <p>
 * 租约回收放在同一轮里：它是一次带索引的条件 UPDATE，代价极低，
 * 而漏掉它的代价是卡死的记录永久停在 PROCESSING。
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "spapi.notifications", name = "enabled", havingValue = "true")
public class NotificationInboxWorkerScheduler {

    private final NotificationInboxWorker worker;
    private final NotificationProperties properties;

    public NotificationInboxWorkerScheduler(NotificationInboxWorker worker,
                                            NotificationProperties properties) {
        this.worker = worker;
        this.properties = properties;
    }

    /** 定时入口：处理失败必须吞掉并继续，否则一次下游抖动就永久停止消费。 */
    @Scheduled(fixedDelayString = "${spapi.notifications.worker-interval-ms:2000}")
    public void run() {
        try {
            runOnce();
        } catch (RuntimeException | Error e) {
            log.error("[NotificationInboxWorkerScheduler] 本轮处理失败，等待下轮重试：error={}",
                    e.getClass().getSimpleName());
        }
    }

    /**
     * 执行一轮处理。
     *
     * @return 本轮实际领取并尝试处理的条数
     */
    public int runOnce() {
        int handled = worker.runBatch(properties.getBatchSize());
        int recovered = worker.recoverStaleLeases(LocalDateTime.now());
        if (handled > 0 || recovered > 0) {
            log.info("[NotificationInboxWorkerScheduler] 本轮处理 {} 条，回收过期租约 {} 条。", handled, recovered);
        }
        return handled;
    }
}
