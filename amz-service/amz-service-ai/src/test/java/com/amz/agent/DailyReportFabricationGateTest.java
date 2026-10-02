package com.amz.agent;

import com.amz.client.MessageServiceClient;
import com.amz.lock.DistributedJobLock;
import com.amz.mapper.UserPreferenceMapper;
import com.amz.model.UserPreference;
import com.amz.scheduler.DailyReportScheduler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.core.env.Environment;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 「模拟经营内容不外发」门禁测试。
 * <p>
 * buildReport() 与 ProactiveReminderService 的正文是写死的示例数字（订单 23、销售额 $1,234.56、
 * SKU B08X4-001…），跟 shopId 无关。过去它们照发不误，用户收到的「昨日经营报告」其实是模板文案。
 * 这里锁定：非 mock 档一个字节都不外发，mock 档才走原逻辑；
 * 这样将来有人删掉门禁，测试会红，而不是等运营拿假数字做决策。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("日报与主动提醒的 mock 门禁")
class DailyReportFabricationGateTest {

    @Mock
    private UserPreferenceMapper userPreferenceMapper;

    @Mock
    private ProactiveReminderService proactiveReminderService;

    @Mock
    private MessageServiceClient messageServiceClient;

    @Mock
    private DistributedJobLock distributedJobLock;

    @Mock
    private Environment environment;

    @InjectMocks
    private DailyReportScheduler scheduler;

    @BeforeEach
    void lockRunsInline() {
        ReflectionTestUtils.setField(scheduler, "environment", environment);
        // 这两个任务体是 void 方法，走 Runnable 重载：测试里同步执行，等于「拿到锁」
        doAnswer(inv -> {
            ((Runnable) inv.getArgument(2)).run();
            return null;
        }).when(distributedJobLock).runWithLock(anyString(), anyLong(), any(Runnable.class));
    }

    private void profiles(String... active) {
        when(environment.getActiveProfiles()).thenReturn(active);
    }

    @Test
    @DisplayName("非 mock：不查用户、不推送，避免把模板数字当真实报告发出去")
    void productionDoesNotPushFabricatedReport() {
        profiles("prod");

        scheduler.pushDailyReport();

        verify(userPreferenceMapper, never()).selectList(any());
        verify(messageServiceClient, never()).notify(any());
    }

    @Test
    @DisplayName("mock：仍按原逻辑推送，演示环境行为不变")
    void mockProfileStillPushes() {
        profiles("mock");
        UserPreference pref = new UserPreference();
        pref.setUserId(7L);
        pref.setPreferredShopId(1L);
        when(userPreferenceMapper.selectList(any())).thenReturn(Collections.singletonList(pref));

        scheduler.pushDailyReport();

        verify(messageServiceClient, times(1)).notify(any());
    }

    @Test
    @DisplayName("非 mock：主动提醒扫描不生成模拟提醒")
    void productionSkipsReminders() {
        profiles("prod");

        scheduler.proactiveReminderScan();

        verify(proactiveReminderService, never()).scanAndRemind();
    }

    @Test
    @DisplayName("mock：主动提醒照旧执行")
    void mockProfileRunsReminders() {
        profiles("mock");
        when(proactiveReminderService.scanAndRemind()).thenReturn(List.of("示例提醒"));

        scheduler.proactiveReminderScan();

        verify(proactiveReminderService, times(1)).scanAndRemind();
    }
}
