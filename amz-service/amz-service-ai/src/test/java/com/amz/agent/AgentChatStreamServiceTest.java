package com.amz.agent;

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
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
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
        UserContext.setUserId(1);
        UserContext.setRole("OPERATOR");
        UserContext.setShops(new ArrayList<>(List.of(1L)));
        UserContext.setShopId(1L);
    }

    @AfterEach
    void cleanup() {
        UserContext.clear();
    }

    @Test
    @DisplayName("成功路径应依次推送 round_started/final/done 并清理监听")
    void testSuccessRoundTrip() throws Exception {
        when(agentService.chat(1L, "hi")).thenReturn(Result.success("hello"));

        service.streamChat("hi", emitter);

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

        service.streamChat("hi", emitter);

        // round_started + error + done
        verify(emitter, timeout(5000).atLeast(3)).send(any(SseEmitter.SseEventBuilder.class));
    }

    @Test
    @DisplayName("worker 应完整传播用户上下文，且任务结束后清理线程变量")
    void testPropagatesAndClearsUserContext() throws Exception {
        ExecutorService singleWorker = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "agent-stream-test");
            t.setDaemon(true);
            return t;
        });
        ReflectionTestUtils.setField(service, "workers", singleWorker);

        UserContext.setUserId(7);
        UserContext.setRole("VIEWER");
        UserContext.setShops(new ArrayList<>(List.of(11L, 12L)));
        UserContext.setShopId(11L);

        AtomicReference<Integer> userId = new AtomicReference<>();
        AtomicReference<String> role = new AtomicReference<>();
        AtomicReference<List<Long>> shops = new AtomicReference<>();
        AtomicReference<Long> shopId = new AtomicReference<>();

        when(agentService.chat(7L, "hi")).thenAnswer(invocation -> {
            userId.set(UserContext.getUserId());
            role.set(UserContext.getRole());
            shops.set(UserContext.getShops());
            shopId.set(UserContext.getShopId());
            return Result.success("hello");
        });

        service.streamChat("hi", emitter);

        verify(agentService, timeout(5000)).chat(7L, "hi");
        assertEquals(7, userId.get(), "worker 应看到 JWT 中的 userId");
        assertEquals("VIEWER", role.get(), "worker 应看到 JWT 中的 role");
        assertEquals(List.of(11L, 12L), shops.get(), "worker 应看到 JWT 中的授权店铺");
        assertEquals(11L, shopId.get(), "worker 应看到请求头中的当前店铺");

        Integer leakedUserId = singleWorker.submit(UserContext::getUserId).get(5, TimeUnit.SECONDS);
        assertNull(leakedUserId, "worker 任务结束后必须清理 ThreadLocal，避免池复用串号");
        assertEquals(7, UserContext.getUserId(), "调用线程身份不应被 worker 清理动作影响");
    }
}
