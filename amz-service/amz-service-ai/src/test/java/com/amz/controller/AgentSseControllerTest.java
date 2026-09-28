package com.amz.controller;

import com.amz.agent.AgentChatStreamService;
import com.amz.context.UserContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("Agent SSE 身份边界测试")
class AgentSseControllerTest {

    @Mock
    private AgentChatStreamService streamService;

    private AgentSseController controller;

    @BeforeEach
    void setUp() {
        controller = new AgentSseController();
        ReflectionTestUtils.setField(controller, "streamService", streamService);
    }

    @AfterEach
    void cleanup() {
        UserContext.clear();
    }

    @Test
    @DisplayName("SSE 身份必须来自认证上下文，且不再接收 userId 查询参数")
    void testIdentityComesFromAuthenticatedContext() throws Exception {
        UserContext.setUserId(7);
        UserContext.setRole("OPERATOR");
        UserContext.setShops(java.util.List.of(11L));
        UserContext.setShopId(11L);

        SseEmitter emitter = controller.chatStream("hi");

        verify(streamService).streamChat("hi", emitter);

        Method method = AgentSseController.class.getMethod("chatStream", String.class);
        for (Parameter parameter : method.getParameters()) {
            RequestParam requestParam = parameter.getAnnotation(RequestParam.class);
            assertFalse(requestParam != null && "userId".equals(requestParam.value()),
                    "SSE 端点不得再信任 userId 查询参数");
        }
    }

    @Test
    @DisplayName("缺少认证上下文时 SSE 端点返回 401，不得默认 userId=1")
    void testMissingIdentityRejected() {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.chatStream("hi"));
        assertEquals(HttpStatus.UNAUTHORIZED, ex.getStatusCode());
    }
}
