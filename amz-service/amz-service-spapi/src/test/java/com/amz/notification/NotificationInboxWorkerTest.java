package com.amz.notification;

import com.amz.mapper.NotificationInboxMapper;
import com.amz.model.NotificationInboxEntity;
import com.amz.util.CryptoUtil;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Inbox Worker 测试。
 * <p>
 * 重点验证三件会静默出错的事：
 * 领取不是原子的（并发下同一事件被处理两次）、毒消息无限重试（拖垮整条队列）、
 * 卡死的记录没人回收（事件永久停滞，界面上看不出异常）。
 */
@SuppressWarnings({"rawtypes", "unchecked"})
class NotificationInboxWorkerTest {

    private static final String RAW = "{\"NotificationId\":\"NOTIF-1\",\"BuyerName\":\"Zhang San\"}";
    private static final String CIPHER = "CIPHER-TEXT";

    private NotificationInboxMapper mapper;
    private CryptoUtil cryptoUtil;
    private NotificationProperties properties;
    private NotificationEventProcessor processor;
    private NotificationInboxWorker worker;

    @BeforeEach
    void setUp() {
        mapper = mock(NotificationInboxMapper.class);
        cryptoUtil = mock(CryptoUtil.class);
        when(cryptoUtil.decrypt(CIPHER)).thenReturn(RAW);
        properties = new NotificationProperties();
        properties.setBaseDelaySeconds(30);
        properties.setMaxAttempts(5);
        properties.setLeaseTimeoutSeconds(300);
        processor = mock(NotificationEventProcessor.class);
        worker = new NotificationInboxWorker(mapper, cryptoUtil, properties, processor);
    }

    @Test
    @DisplayName("领取成功后处理并标记 PROCESSED，释放租约")
    void claimedEventIsProcessedAndMarkedProcessed() {
        givenDueEvent(event(1L, 1, 5));

        assertEquals(1, worker.runBatch(10));
        verify(processor).process(any(), org.mockito.ArgumentMatchers.eq(RAW));
        List<UpdateWrapper> updates = capturedUpdates();
        assertEquals(2, updates.size());
        assertTrue(updates.get(1).getParamNameValuePairs().values()
                .contains(NotificationInboxStatus.PROCESSED));
        assertTrue(updates.get(1).getSqlSet().contains("lease_owner = NULL"));
    }

    @Test
    @DisplayName("领取竞争失败（影响行数 0）：绝不处理，避免重复副作用")
    void lostRaceIsSkipped() {
        givenDueEvent(event(1L, 1, 5));
        when(mapper.update(isNull(), any(UpdateWrapper.class))).thenReturn(0);

        assertEquals(0, worker.runBatch(10));
        verifyNoInteractions(processor);
        verify(mapper, never()).selectById(any());
    }

    @Test
    @DisplayName("领取必须是条件 UPDATE，且 attempt_count 由 SQL 原子自增")
    void claimIsAtomicConditionalUpdate() {
        givenDueEvent(event(1L, 1, 5));
        worker.runBatch(10);

        UpdateWrapper claim = capturedUpdates().get(0);
        assertTrue(claim.getSqlSet().contains("attempt_count = attempt_count + 1"),
                "attempt_count 必须由 SQL 自增，不能用 Java 读出的旧值覆盖");
        assertTrue(claim.getParamNameValuePairs().values().contains(NotificationInboxStatus.PROCESSING));
        assertTrue(claim.getSqlSegment().contains("next_attempt_at"),
                "WHERE 必须带 next_attempt_at 条件，否则未到期的事件会被提前领取");
    }

    @Test
    @DisplayName("可重试失败且有额度：回 RECEIVED 并按指数退避排期")
    void retryableFailureIsRescheduled() {
        givenDueEvent(event(1L, 1, 5));
        failWith(NotificationProcessingException.retryable("DOWNSTREAM_UNAVAILABLE", "下游不可用"));

        UpdateWrapper outcome = capturedUpdates().get(1);
        assertTrue(outcome.getParamNameValuePairs().values().contains(NotificationInboxStatus.RECEIVED));
        assertTrue(outcome.getParamNameValuePairs().values().stream()
                .anyMatch(v -> v instanceof LocalDateTime t && t.isAfter(LocalDateTime.now())));
    }

    @Test
    @DisplayName("重试次数耗尽：进 DLQ，不再排期")
    void exhaustedAttemptsGoToDlq() {
        givenDueEvent(event(1L, 5, 5));
        failWith(NotificationProcessingException.retryable("DOWNSTREAM_UNAVAILABLE", "下游不可用"));

        UpdateWrapper outcome = capturedUpdates().get(1);
        assertTrue(outcome.getParamNameValuePairs().values().contains(NotificationInboxStatus.DLQ));
    }

    @Test
    @DisplayName("不可重试失败：即使还有重试额度也直接进 DLQ")
    void nonRetryableFailureGoesStraightToDlq() {
        givenDueEvent(event(1L, 1, 5));
        failWith(NotificationProcessingException.fatal("UNSUPPORTED_TYPE", "类型不支持"));

        assertTrue(capturedUpdates().get(1).getParamNameValuePairs().values()
                .contains(NotificationInboxStatus.DLQ));
    }

    @Test
    @DisplayName("未声明语义的运行时异常：兜底按可重试处理，错误码 UNEXPECTED")
    void unexpectedExceptionIsTreatedAsRetryable() {
        givenDueEvent(event(1L, 1, 5));
        org.mockito.Mockito.doThrow(new IllegalStateException("连接被重置"))
                .when(processor).process(any(), any());

        worker.runBatch(10);

        UpdateWrapper outcome = capturedUpdates().get(1);
        assertTrue(outcome.getParamNameValuePairs().values().contains(NotificationInboxStatus.RECEIVED));
        assertTrue(outcome.getParamNameValuePairs().values()
                .contains(NotificationInboxWorker.ERR_UNEXPECTED));
    }

    @Test
    @DisplayName("错误摘要只记录异常类型，绝不把异常消息写库（消息可能含 PII）")
    void errorSummaryNeverContainsExceptionMessage() {
        givenDueEvent(event(1L, 1, 5));
        failWith(NotificationProcessingException.retryable("DOWNSTREAM_UNAVAILABLE",
                "下游不可用，订单 903-1000001-2000001 买家 Zhang San"));

        UpdateWrapper outcome = capturedUpdates().get(1);
        assertTrue(outcome.getParamNameValuePairs().values()
                .contains(NotificationProcessingException.class.getSimpleName()));
        assertFalse(outcome.getParamNameValuePairs().values().stream()
                .anyMatch(v -> v instanceof String s && s.contains("Zhang San")));
    }

    @Test
    @DisplayName("租约过期：PROCESSING 记录回收回 RECEIVED 并清空租约字段")
    void staleLeasesAreRecovered() {
        when(mapper.update(isNull(), any(UpdateWrapper.class))).thenReturn(3);

        assertEquals(3, worker.recoverStaleLeases(LocalDateTime.now()));
        UpdateWrapper w = capturedUpdates().get(0);
        assertTrue(w.getParamNameValuePairs().values().contains(NotificationInboxStatus.RECEIVED));
        assertTrue(w.getSqlSet().contains("lease_owner = NULL"));
        assertTrue(w.getSqlSet().contains("lease_until = NULL"));
    }

    @Test
    @DisplayName("租约标识非空，便于定位是哪个实例卡住")
    void leaseOwnerIsIdentifiable() {
        assertNotNull(worker.getLeaseOwner());
        assertFalse(worker.getLeaseOwner().isBlank());
    }

    private void givenDueEvent(NotificationInboxEntity event) {
        when(mapper.selectList(any())).thenReturn(List.of(event));
        when(mapper.selectById(event.getId())).thenReturn(event);
        when(mapper.update(isNull(), any(UpdateWrapper.class))).thenReturn(1);
    }

    /** 让处理器抛出指定异常并跑完一轮，返回后即可断言落库结果。 */
    private void failWith(RuntimeException e) {
        org.mockito.Mockito.doThrow(e).when(processor).process(any(), any());
        worker.runBatch(10);
    }

    private List<UpdateWrapper> capturedUpdates() {
        ArgumentCaptor<UpdateWrapper> captor = ArgumentCaptor.forClass(UpdateWrapper.class);
        verify(mapper, atLeastOnce()).update(isNull(), captor.capture());
        return captor.getAllValues();
    }

    private static NotificationInboxEntity event(Long id, int attempt, int maxAttempts) {
        NotificationInboxEntity e = new NotificationInboxEntity();
        e.setId(id);
        e.setNotificationId("NOTIF-1");
        e.setNotificationType("ORDER_CHANGE");
        e.setShopId(7L);
        e.setAttemptCount(attempt);
        e.setMaxAttempts(maxAttempts);
        e.setPayloadEncrypted(CIPHER);
        return e;
    }
}