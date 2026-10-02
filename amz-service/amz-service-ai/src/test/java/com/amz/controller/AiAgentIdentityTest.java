package com.amz.controller;

import com.amz.agent.langchain4j.LangChain4jAgentService;
import com.amz.context.UserContext;
import com.amz.result.Result;
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

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * /ai/erp/agent 的身份来源测试。
 * <p>
 * 该端点过去把 userId 做成 {@code @RequestParam(defaultValue="1")}，而
 * {@code LangChain4jAgentService#chat} 用它拼 ChatMemory 会话键（"sess-" + userId）。
 * 会话键就是记忆归属：任何登录用户改一下 query 就能读写别人的对话上下文，
 * 不登录也会统一落到 userId=1。身份必须只来自认证上下文。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ERP Agent 端点身份测试")
class AiAgentIdentityTest {

    @Mock
    private LangChain4jAgentService langChain4jAgentService;

    private AiController controller;

    @BeforeEach
    void setUp() {
        controller = new AiController();
        ReflectionTestUtils.setField(controller, "langChain4jAgentService", langChain4jAgentService);
        UserContext.setUserId(7);
        UserContext.setRole("VIEWER");
    }

    @AfterEach
    void cleanup() {
        UserContext.clear();
    }

    @Test
    @DisplayName("方法签名不得再接收 userId 参数")
    void erpAgentHasNoUserIdRequestParam() throws Exception {
        Method method = AiController.class.getMethod("erpAgent", AiController.ErpAgentRequest.class);
        for (Parameter parameter : method.getParameters()) {
            RequestParam requestParam = parameter.getAnnotation(RequestParam.class);
            assertFalse(requestParam != null && "userId".equals(requestParam.value()),
                    "erpAgent 不得再接收 userId 参数");
        }
    }

    @Test
    @DisplayName("会话键来自认证用户，而不是客户端自报的身份")
    void erpAgentUsesAuthenticatedUser() {
        when(langChain4jAgentService.chat(7L, "最近7天订单如何？")).thenReturn(Result.success("ok"));

        AiController.ErpAgentRequest request = new AiController.ErpAgentRequest();
        request.setMessage("最近7天订单如何？");
        Result<String> result = controller.erpAgent(request);

        assertEquals("ok", result.getData());
        verify(langChain4jAgentService).chat(7L, "最近7天订单如何？");
    }

    @Test
    @DisplayName("未登录时拒绝，不落到任何默认用户")
    void erpAgentRequiresIdentity() {
        UserContext.clear();

        AiController.ErpAgentRequest request = new AiController.ErpAgentRequest();
        request.setMessage("hi");
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.erpAgent(request));

        assertEquals(HttpStatus.UNAUTHORIZED, ex.getStatusCode());
        verifyNoInteractions(langChain4jAgentService);
    }

    @Test
    @DisplayName("空消息直接失败，不打到模型")
    void erpAgentRejectsBlankMessage() {
        AiController.ErpAgentRequest request = new AiController.ErpAgentRequest();
        request.setMessage("   ");

        Result<String> result = controller.erpAgent(request);

        assertEquals(400, result.getCode());
        assertNull(result.getData());
        verifyNoInteractions(langChain4jAgentService);
    }
}
