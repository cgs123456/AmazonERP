package com.amz.agent;

import com.amz.agent.langchain4j.LangChain4jAgentService;
import com.amz.result.Result;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Agent SSE 流式服务测试（emitter/agent 全 mock，不依赖模型与容器）。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Agent SSE 流式服务测试")
class AgentChatStreamServiceTest {

    @Mock
    private LangChain4jAgentService agentService;

    @Mock
    private SseEmitter emitter;

    private AgentChatStreamService service;

    @BeforeEach
    void setUp() {
        service = new AgentChatStreamService();
        ReflectionTestUtils.setField(service, "agentService", agentService);
    }

    @Test
    @DisplayName("成功路径应依次推送 round_started/final/done 并清理监听")
    void testSuccessRoundTrip() throws Exception {
        when(agentService.chat(1L, "hi")).thenReturn(Result.success("hello"));

        service.streamChat(1L, "hi", emitter);

        // round_started + final + done，共至少 3 次发送
        // （SseEmitter 只有 send(SseEventBuilder) 可 mock，注意非 send(Object)）
        verify(emitter, timeout(5000).atLeast(3)).send(any(SseEmitter.SseEventBuilder.class));
        // 监听器最终被清理（轮询等待工作线程收尾）
        long deadline = System.currentTimeMillis() + 3000;
        while (AgentToolEventBus.listenerCount() != 0 && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                fail("等待监听清理被中断");
            }
        }
        assertEquals(0, AgentToolEventBus.listenerCount(), "流结束后 trace 监听应被清理");
    }

    @Test
    @DisplayName("Agent 失败应推送 error 事件而非抛异常")
    void testAgentFailureEmitsError() throws Exception {
        when(agentService.chat(1L, "hi")).thenReturn(Result.failure("boom"));

        service.streamChat(1L, "hi", emitter);

        // round_started + error + done
        verify(emitter, timeout(5000).atLeast(3)).send(any(SseEmitter.SseEventBuilder.class));
    }
}
