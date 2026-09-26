package com.amz.notification;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NotificationMetricsTest {

    private SimpleMeterRegistry registry;
    private NotificationMetrics metrics;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        metrics = new NotificationMetrics(registry);
    }

    @Test
    @DisplayName("指标名统一带 amz.spapi.notification 前缀，且按类型打标签")
    void registeredWithPrefixAndTypeTag() {
        metrics.received("ORDER_CHANGE");
        metrics.received("ORDER_CHANGE");
        metrics.received("FEED_PROCESSING_FINISHED");

        assertEquals(2.0, metrics.counter("received", "type", "ORDER_CHANGE").count());
        assertEquals(1.0, metrics.counter("received", "type", "FEED_PROCESSING_FINISHED").count());
        assertTrue(registry.getMeters().stream()
                .anyMatch(m -> m.getId().getName().equals("amz.spapi.notification.received")));
    }

    @Test
    @DisplayName("外部输入做标签归一化：空值兜底、超长截断，防止时间序列基数被打爆")
    void tagNormalization() {
        assertEquals("unknown", NotificationMetrics.tag(null));
        assertEquals("unknown", NotificationMetrics.tag("   "));
        assertEquals("ORDER_CHANGE", NotificationMetrics.tag("  ORDER_CHANGE "));

        String tooLong = "T".repeat(200);
        assertEquals(64, NotificationMetrics.tag(tooLong).length());

        metrics.unsupported(null);
        assertEquals(1.0, metrics.counter("unsupported", "type", "unknown").count());
    }

    @Test
    @DisplayName("成功/重试/DLQ 分别落在不同的指标上，便于分别告警")
    void outcomeCounters() {
        metrics.processed("ORDER_CHANGE", 1_000_000L);
        metrics.retryScheduled();
        metrics.dlq(NotificationInboxStatus.DLQ);
        metrics.dlq(NotificationInboxStatus.UNSUPPORTED);

        assertEquals(1.0, metrics.counter("processed", "type", "ORDER_CHANGE").count());
        assertEquals(1.0, metrics.counter("retry.scheduled").count());
        assertEquals(1.0, metrics.counter("dlq", "status", "DLQ").count());
        assertEquals(1.0, metrics.counter("dlq", "status", "UNSUPPORTED").count());
        assertTrue(registry.find("amz.spapi.notification.handler.duration").timer() != null,
                "处理耗时必须有 Timer，否则无法发现 Handler 变慢");
    }

    @Test
    @DisplayName("Inbox 滞留以 Gauge 暴露，取当前值而不是累计值")
    void inboxLagGauge() {
        assertEquals(0L, metrics.inboxLagSeconds());
        metrics.recordInboxLagSeconds(120L);
        assertEquals(120L, metrics.inboxLagSeconds());
        assertEquals(120.0, registry.find("amz.spapi.notification.inbox.lag.seconds").gauge().value());

        metrics.recordInboxLagSeconds(-5L);
        assertEquals(0L, metrics.inboxLagSeconds(), "滞留秒数不能为负");
    }

    @Test
    @DisplayName("重复投递按哈希是否一致分别计数：同哈希是预期内重投，异哈希是异常")
    void duplicateCounters() {
        metrics.duplicateSameHash();
        metrics.duplicateSameHash();
        metrics.duplicateDiffHash();

        assertEquals(2.0, metrics.counter("duplicate").count());
        assertEquals(1.0, metrics.counter("duplicate.diff").count());
    }
}