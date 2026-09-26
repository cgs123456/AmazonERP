package com.amz.notification;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 重试策略测试：退避必须翻倍、必须封顶，否则毒消息会把重试拉到不可观测的时间尺度。 */
class NotificationRetryPolicyTest {

    private final NotificationRetryPolicy policy = new NotificationRetryPolicy(30);

    @Test
    @DisplayName("首次失败退避基础时长")
    void firstAttemptUsesBaseDelay() {
        assertEquals(30, policy.nextDelaySeconds(1));
    }

    @Test
    @DisplayName("退避随次数翻倍")
    void delayDoublesPerAttempt() {
        assertEquals(30, policy.nextDelaySeconds(1));
        assertEquals(60, policy.nextDelaySeconds(2));
        assertEquals(120, policy.nextDelaySeconds(3));
        assertEquals(240, policy.nextDelaySeconds(4));
    }

    @Test
    @DisplayName("退避封顶，不随次数无限增长")
    void delayIsCapped() {
        assertEquals(NotificationRetryPolicy.MAX_DELAY_SECONDS, policy.nextDelaySeconds(50));
    }

    @Test
    @DisplayName("次数小于 1 时按首次处理，不产生 0 或负数退避")
    void attemptBelowOneIsTreatedAsFirst() {
        assertEquals(30, policy.nextDelaySeconds(0));
        assertEquals(30, policy.nextDelaySeconds(-5));
    }

    @Test
    @DisplayName("只有达到 max_attempts 才进 DLQ")
    void deadLetterOnlyAtMaxAttempts() {
        assertFalse(policy.shouldDeadLetter(1, 5));
        assertFalse(policy.shouldDeadLetter(4, 5));
        assertTrue(policy.shouldDeadLetter(5, 5));
        assertTrue(policy.shouldDeadLetter(9, 5));
    }

    @Test
    @DisplayName("基础退避非正数时拒绝构造")
    void nonPositiveBaseDelayIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new NotificationRetryPolicy(0));
    }
}