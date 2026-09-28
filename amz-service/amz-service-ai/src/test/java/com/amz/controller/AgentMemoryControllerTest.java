package com.amz.controller;

import com.amz.agent.MemoryAwareAgentService;
import com.amz.agent.ProactiveReminderService;
import com.amz.annotation.RequireRole;
import com.amz.context.UserContext;
import com.amz.model.UserPreference;
import com.amz.result.Result;
import com.amz.service.MemoryService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("Agent 记忆接口身份与授权测试")
class AgentMemoryControllerTest {

    @Mock
    private MemoryAwareAgentService memoryAwareAgentService;

    @Mock
    private MemoryService memoryService;

    @Mock
    private ProactiveReminderService proactiveReminderService;

    private AgentMemoryController controller;

    @BeforeEach
    void setUp() {
        controller = new AgentMemoryController();
        ReflectionTestUtils.setField(controller, "memoryAwareAgentService", memoryAwareAgentService);
        ReflectionTestUtils.setField(controller, "memoryService", memoryService);
        ReflectionTestUtils.setField(controller, "proactiveReminderService", proactiveReminderService);
        UserContext.setUserId(7);
        UserContext.setRole("VIEWER");
    }

    @AfterEach
    void cleanup() {
        UserContext.clear();
    }

    @Test
    @DisplayName("对话和语言切换必须使用认证上下文用户，不再接收 userId 参数")
    void testMutatingEndpointsUseAuthenticatedIdentity() throws Exception {
        AgentMemoryController.ChatRequest request = new AgentMemoryController.ChatRequest();
        request.setMessage("hi");
        when(memoryAwareAgentService.chat(7L, "hi")).thenReturn(Result.success("ok"));
        controller.chat(request);
        verify(memoryAwareAgentService).chat(7L, "hi");

        UserPreference pref = new UserPreference();
        pref.setUserId(7L);
        pref.setLanguage("ZH");
        when(memoryService.getOrCreatePreference(7L)).thenReturn(pref);
        when(memoryService.updatePreference(pref)).thenReturn(pref);
        controller.switchLanguage("EN");
        verify(memoryService).getOrCreatePreference(7L);
        assertEquals("EN", pref.getLanguage());

        assertNoUserIdRequestParam(AgentMemoryController.class.getMethod("chat", AgentMemoryController.ChatRequest.class));
        assertNoUserIdRequestParam(AgentMemoryController.class.getMethod("switchLanguage", String.class));
    }

    @Test
    @DisplayName("更新偏好不得通过 body 中的 id/userId 越权覆盖他人数据")
    void testUpdatePreferenceCannotSpoofUserIdOrId() {
        UserPreference spoofed = new UserPreference();
        spoofed.setId(99L);
        spoofed.setUserId(9L);
        when(memoryService.updatePreference(any(UserPreference.class))).thenAnswer(invocation -> invocation.getArgument(0));

        controller.updatePreference(spoofed);

        ArgumentCaptor<UserPreference> captor = ArgumentCaptor.forClass(UserPreference.class);
        verify(memoryService).updatePreference(captor.capture());
        assertEquals(7L, captor.getValue().getUserId(), "userId 必须强制覆盖为认证用户");
        assertNull(captor.getValue().getId(), "id 必须清空并按认证用户重新解析，防止 updateById 越权");
    }

    @Test
    @DisplayName("普通用户读取他人偏好或历史必须返回 403")
    void testCrossUserReadsRejected() {
        ResponseStatusException preferenceEx = assertThrows(ResponseStatusException.class,
                () -> controller.getPreference(9L));
        assertEquals(HttpStatus.FORBIDDEN, preferenceEx.getStatusCode());

        ResponseStatusException historyEx = assertThrows(ResponseStatusException.class,
                () -> controller.history(9L, 10));
        assertEquals(HttpStatus.FORBIDDEN, historyEx.getStatusCode());
    }

    @Test
    @DisplayName("ADMIN 可读取指定用户偏好，但仍不能伪造身份写入")
    void testAdminCanReadOtherUserPreference() {
        UserContext.setRole("ADMIN");
        UserPreference pref = new UserPreference();
        pref.setUserId(9L);
        when(memoryService.getOrCreatePreference(9L)).thenReturn(pref);

        Result<UserPreference> result = controller.getPreference(9L);

        assertSame(pref, result.getData());
        verify(memoryService).getOrCreatePreference(9L);
    }

    @Test
    @DisplayName("主动提醒扫描仅允许 ADMIN")
    void testReminderScanRequiresAdminRole() throws Exception {
        Method method = AgentMemoryController.class.getMethod("scanReminders");
        RequireRole requireRole = method.getAnnotation(RequireRole.class);
        assertEquals(requireRole != null, true, "scanReminders 必须标注 @RequireRole");
        assertArrayEquals(new String[]{"ADMIN"}, requireRole.value());
    }

    @Test
    @DisplayName("缺少认证上下文时记忆接口返回 401")
    void testMissingIdentityRejected() {
        UserContext.clear();
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.getPreference(7L));
        assertEquals(HttpStatus.UNAUTHORIZED, ex.getStatusCode());
    }

    private static void assertNoUserIdRequestParam(Method method) {
        for (Parameter parameter : method.getParameters()) {
            RequestParam requestParam = parameter.getAnnotation(RequestParam.class);
            assertFalse(requestParam != null && "userId".equals(requestParam.value()),
                    method.getName() + " 不得再接收 userId 参数");
        }
    }
}
