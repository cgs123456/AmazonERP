package com.amz.service.impl;

import com.amz.exception.CodeErrorException;
import com.amz.lock.DistributedJobLock;
import com.amz.mapper.HijackAlertMapper;
import com.amz.mapper.KeywordRankRecordMapper;
import com.amz.mapper.NegativeReviewAlertMapper;
import com.amz.mapper.ShopMapper;
import com.amz.scheduler.OpsMonitorScheduler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.core.env.Environment;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 三条模拟扫描在**非 mock 档**必须显式拒绝，定时任务则整轮跳过。
 * <p>
 * 旧行为是 {@code return 0}：控制器包成 {@code Result.success(0)}，调用方看到「扫描成功、
 * 新增 0 条告警」，与「真的扫过但一条都没有」完全无法分辨——等于把没实现的能力说成已实现。
 * 改成抛业务拒绝后，调度器不能再走老路（否则每家店铺每轮刷一条 ERROR），所以在入口判档跳过。
 * <p>
 * 这里刻意把分布式锁的回调真的执行掉：{@code runWithLock} 若只当普通 mock 用，任务体根本不会跑，
 * 「跳过了」和「没跑到」两种情况断言起来一模一样，是典型的假绿。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("运营扫描：非 mock 显式拒绝，定时任务整轮跳过")
class OpsScanNonMockRefusalTest {

    @Mock
    private NegativeReviewAlertMapper reviewAlertMapper;

    @Mock
    private HijackAlertMapper hijackAlertMapper;

    @Mock
    private KeywordRankRecordMapper rankMapper;

    @Mock
    private Environment environment;

    @Mock
    private ShopMapper shopMapper;

    @Mock
    private DistributedJobLock distributedJobLock;

    @InjectMocks
    private OpsServiceImpl opsService;

    @InjectMocks
    private OpsMonitorScheduler scheduler;

    private void nonMock() {
        when(environment.acceptsProfiles(any(org.springframework.core.env.Profiles.class))).thenReturn(false);
        when(environment.getActiveProfiles()).thenReturn(new String[]{"prod"});
    }

    /** 让锁真的执行任务体，否则这条测试什么都证明不了。 */
    private void lockRunsTheJob() {
        doAnswer(inv -> {
            inv.getArgument(2, Runnable.class).run();
            return null;
        }).when(distributedJobLock).runWithLock(anyString(), anyLong(), any(Runnable.class));
    }

    @Test
    @DisplayName("三条扫描都抛业务拒绝，且一行都不写")
    void allThreeScansRefuseOutsideMock() {
        nonMock();

        CodeErrorException a = assertThrows(CodeErrorException.class, () -> opsService.scanNegativeReviews(1L));
        CodeErrorException b = assertThrows(CodeErrorException.class, () -> opsService.scanHijackers(1L));
        CodeErrorException c = assertThrows(CodeErrorException.class, () -> opsService.captureKeywordRanks(1L));

        for (CodeErrorException ex : new CodeErrorException[]{a, b, c}) {
            assertTrue(ex.getMessage().contains("未接入真实数据源"), ex.getMessage());
            assertTrue(ex.getMessage().contains("mock"), ex.getMessage());
        }
        verifyNoInteractions(reviewAlertMapper, hijackAlertMapper, rankMapper);
    }

    @Test
    @DisplayName("定时任务在非 mock 档整轮跳过：连店铺列表都不查")
    void schedulerSkipsOutsideMock() {
        nonMock();
        lockRunsTheJob();

        scheduler.dailyScan();
        scheduler.rankCapture();

        verifyNoInteractions(shopMapper, reviewAlertMapper, hijackAlertMapper, rankMapper);
    }

    @Test
    @DisplayName("正向对照：mock 档下锁确实会执行任务体（证明上一条不是假绿）")
    void positiveControlLockActuallyRunsTheBody() {
        when(environment.acceptsProfiles(any(org.springframework.core.env.Profiles.class))).thenReturn(true);
        when(environment.getActiveProfiles()).thenReturn(new String[]{"mock"});
        lockRunsTheJob();
        when(shopMapper.selectList(any())).thenReturn(java.util.List.of());

        scheduler.dailyScan();

        // 走到任务体才会查店铺；若锁没执行回调，这条断言会失败
        assertTrue(org.mockito.Mockito.mockingDetails(shopMapper).getInvocations().size() > 0,
                "mock 档下任务体应真的跑起来并查店铺列表");
    }
}
