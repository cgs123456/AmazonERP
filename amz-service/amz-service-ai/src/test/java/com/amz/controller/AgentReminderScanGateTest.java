package com.amz.controller;

import com.amz.agent.ProactiveReminderService;
import com.amz.context.UserContext;
import com.amz.result.Result;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.env.Environment;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * POST /ai/agent/memory/reminder/scan 的造数门禁测试。
 * <p>
 * 提醒正文来自 {@code ProactiveReminderService#remindForUser} 的写死示例
 * （SKU B08X4-001、可售 4 天、18 单降 28%、1 条 2 星差评、1 个跟卖者），
 * 与 shopId 无关。定时任务侧早有 mock 档门禁，但这个 HTTP 入口绕过了它：
 * ADMIN 一次 curl 就能批量生成假告警，且 scanAndRemind() 会把它们推到 IM。
 * 门禁必须落在入口处，未显式声明 mock 档一律拒绝。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("主动提醒扫描的 mock 档门禁")
class AgentReminderScanGateTest {

    @Mock
    private ProactiveReminderService proactiveReminderService;

    @Mock
    private Environment environment;

    private AgentMemoryController controller;

    @BeforeEach
    void setUp() {
        controller = new AgentMemoryController();
        ReflectionTestUtils.setField(controller, "proactiveReminderService", proactiveReminderService);
        ReflectionTestUtils.setField(controller, "environment", environment);
        UserContext.setUserId(1);
        UserContext.setRole("ADMIN");
    }

    @AfterEach
    void cleanup() {
        UserContext.clear();
    }

    @Test
    @DisplayName("prod 档：不调度扫描、不推 IM，并说明原因")
    void nonMockProfileRefuses() {
        when(environment.getActiveProfiles()).thenReturn(new String[]{"prod"});

        Result<List<String>> result = controller.scanReminders();

        assertEquals(400, result.getCode());
        assertTrue(result.getMessage().contains("mock"), "拒绝原因要能让调用方知道是档位限制：" + result.getMessage());
        verify(proactiveReminderService, never()).scanAndRemind();
    }

    @Test
    @DisplayName("未配置 active profile 时按非 mock 处理（缺省即拒绝，不靠猜）")
    void unsetProfileRefuses() {
        when(environment.getActiveProfiles()).thenReturn(new String[]{});

        Result<List<String>> result = controller.scanReminders();

        assertEquals(400, result.getCode());
        verify(proactiveReminderService, never()).scanAndRemind();
    }

    @Test
    @DisplayName("mock 档：照旧执行，演示环境行为不变")
    void mockProfileRunsScan() {
        when(environment.getActiveProfiles()).thenReturn(new String[]{"dev", "mock"});
        when(proactiveReminderService.scanAndRemind()).thenReturn(List.of("示例提醒"));

        Result<List<String>> result = controller.scanReminders();

        assertEquals(200, result.getCode());
        assertEquals(List.of("示例提醒"), result.getData());
        verify(proactiveReminderService).scanAndRemind();
    }
}
