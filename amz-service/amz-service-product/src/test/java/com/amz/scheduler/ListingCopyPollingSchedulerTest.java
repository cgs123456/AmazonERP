package com.amz.scheduler;

import com.amz.lock.DistributedJobLock;
import com.amz.service.ListingCopyService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ListingCopyPollingSchedulerTest {

    @Mock
    private ListingCopyService listingCopyService;

    @Spy
    private DistributedJobLock distributedJobLock = new ImmediateDistributedJobLock();

    @InjectMocks
    private ListingCopyPollingScheduler scheduler;

    @Test
    @DisplayName("轮询开关关闭时不得访问数据库或下游服务")
    void disabledSchedulerDoesNotPoll() {
        ReflectionTestUtils.setField(scheduler, "pollEnabled", false);

        scheduler.pollDueFeedTasks();

        verify(listingCopyService, never()).pollDueFeedTasks(org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    @DisplayName("启用后按配置批量扫描到期任务")
    void enabledSchedulerPollsConfiguredBatchSize() {
        ReflectionTestUtils.setField(scheduler, "pollEnabled", true);
        ReflectionTestUtils.setField(scheduler, "batchSize", 25);
        when(listingCopyService.pollDueFeedTasks(25)).thenReturn(3);

        scheduler.pollDueFeedTasks();

        verify(listingCopyService).pollDueFeedTasks(25);
    }

    @Test
    @DisplayName("单轮扫描异常不能终止后续定时调度")
    void pollFailureDoesNotEscapeScheduler() {
        ReflectionTestUtils.setField(scheduler, "pollEnabled", true);
        ReflectionTestUtils.setField(scheduler, "batchSize", 10);
        when(listingCopyService.pollDueFeedTasks(10))
                .thenThrow(new IllegalStateException("database unavailable"));

        assertDoesNotThrow(scheduler::pollDueFeedTasks);
    }

    /**
     * 调度器测试关注业务动作本身；锁的抢锁/降级/fail-closed 语义由
     * amz-common 的 DistributedJobLockTest 独立覆盖。
     */
    private static class ImmediateDistributedJobLock extends DistributedJobLock {
        @Override
        public <T> T runWithLock(String lockKey, long leaseSeconds, Supplier<T> action, T fallback) {
            return action.get();
        }
    }
}
