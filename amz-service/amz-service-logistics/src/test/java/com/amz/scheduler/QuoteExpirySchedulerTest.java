package com.amz.scheduler;

import com.amz.lock.DistributedJobLock;
import com.amz.mapper.CarrierQuoteMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 过期运价收口调度器。
 * <p>
 * <b>为什么需要这些测试</b>：这个调度器做的是「按日期批量改状态」，三类失败都不会
 * 让编译或既有测试变红：
 * <ul>
 *   <li>开关判断反了（{@code enabled=false} 时仍执行）→ 想临时关掉收口时关不掉；</li>
 *   <li>异常外抛 → 调度线程被 Spring 记录，但日志里看不出「是本任务失败」；</li>
 *   <li>用严格锁而非幂等降级 → Redis 故障期间报价一直不过期，
 *       而这恰恰是「过期的运价继续在比价里被当成有效」的窗口。</li>
 * </ul>
 */
@DisplayName("过期运价收口调度：开关生效、异常不外抛、幂等锁降级、日期边界")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class QuoteExpirySchedulerTest {

    @Mock
    private CarrierQuoteMapper carrierQuoteMapper;

    @Mock
    private DistributedJobLock distributedJobLock;

    private QuoteExpiryScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new QuoteExpiryScheduler();
        ReflectionTestUtils.setField(scheduler, "carrierQuoteMapper", carrierQuoteMapper);
        ReflectionTestUtils.setField(scheduler, "distributedJobLock", distributedJobLock);
        ReflectionTestUtils.setField(scheduler, "enabled", true);

        // 让锁直接执行 action，模拟「抢到锁」（或 Redis 不可用时的降级执行）
        when(distributedJobLock.runIdempotentWithLock(anyString(), anyLong(), any(), any()))
                .thenAnswer(inv -> ((Supplier<?>) inv.getArgument(2)).get());
    }

    @Test
    @DisplayName("默认启用：以当天为界执行跨店铺收口")
    void runsAcrossShopsWhenEnabled() {
        when(carrierQuoteMapper.expireOutdatedAcrossShops(any())).thenReturn(2);

        scheduler.expireOutdatedQuotes();

        ArgumentCaptor<LocalDate> captor = ArgumentCaptor.forClass(LocalDate.class);
        verify(carrierQuoteMapper).expireOutdatedAcrossShops(captor.capture());
        // 边界是「今天」：expiry_date < today 才过期，当天到期的仍算有效。
        // 若误写成 <= today，当天到期的报价会在到期日当天的第一轮就被判失效。
        assertEquals(LocalDate.now(), captor.getValue());
    }

    @Test
    @DisplayName("开关关闭：不碰数据库，也不去抢锁")
    void skipsWhenDisabled() {
        ReflectionTestUtils.setField(scheduler, "enabled", false);

        scheduler.expireOutdatedQuotes();

        verify(carrierQuoteMapper, never()).expireOutdatedAcrossShops(any());
        verify(distributedJobLock, never())
                .runIdempotentWithLock(anyString(), anyLong(), any(), any());
    }

    @Test
    @DisplayName("Mapper 抛异常：不外抛，由下一轮重试")
    void doesNotPropagateFailure() {
        when(carrierQuoteMapper.expireOutdatedAcrossShops(any()))
                .thenThrow(new IllegalStateException("db down"));

        assertDoesNotThrow(() -> scheduler.expireOutdatedQuotes());

        // 仍走完了一轮（说明异常被调度器内部消化，而不是阻止下一轮）
        verify(carrierQuoteMapper).expireOutdatedAcrossShops(any());
    }

    @Test
    @DisplayName("使用幂等降级入口而非严格锁：Redis 故障时报价仍能过期")
    void usesIdempotentLock() {
        scheduler.expireOutdatedQuotes();

        // 关键断言：调的是 runIdempotentWithLock。若有人改成 runWithLock，
        // Redis 不可用时本任务会被跳过，报价在故障期间一直不过期。
        verify(distributedJobLock).runIdempotentWithLock(
                eq("logistics:quote-expiry"), eq(300L), any(), eq(null));
    }
}
