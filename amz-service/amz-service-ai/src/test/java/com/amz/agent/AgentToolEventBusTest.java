package com.amz.agent;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Agent 追踪上下文 + 工具事件总线单元测试（纯内存，无 Spring/网络）。
 */
@DisplayName("Agent 追踪上下文与事件总线测试")
class AgentToolEventBusTest {

    @AfterEach
    void cleanup() {
        AgentTraceContext.clear();
        AgentToolEventBus.unregister("tr-test");
    }

    @Test
    @DisplayName("TraceContext 存取与清理")
    void testTraceContext() {
        AgentTraceContext.set("tr-test");
        assertEquals("tr-test", AgentTraceContext.get());
        AgentTraceContext.clear();
        assertNull(AgentTraceContext.get());
    }

    @Test
    @DisplayName("注册后发布应送达，未注册 trace 应静默跳过")
    void testPublishRoutesByTraceId() {
        List<AgentToolEvent> received = new ArrayList<>();
        AgentToolEventBus.register("tr-test", received::add);

        AgentToolEventBus.publish("tr-test", AgentToolEvent.call("query_orders", "{}"));
        AgentToolEventBus.publish("tr-other", AgentToolEvent.call("query_orders", "{}"));
        AgentToolEventBus.publish(null, AgentToolEvent.call("query_orders", "{}"));

        assertEquals(1, received.size());
        assertEquals("tool_call", received.get(0).getType());
        assertEquals("query_orders", received.get(0).getName());
    }

    @Test
    @DisplayName("监听器抛异常应被隔离，不影响发布流程")
    void testListenerExceptionIsolated() {
        AgentToolEventBus.register("tr-test", event -> {
            throw new RuntimeException("listener boom");
        });
        // 不应抛到调用方（工具执行主流程）
        AgentToolEventBus.publish("tr-test", AgentToolEvent.done());
    }

    @Test
    @DisplayName("unregister 后不再投递")
    void testUnregisterStopsDelivery() {
        List<AgentToolEvent> received = new ArrayList<>();
        AgentToolEventBus.register("tr-test", received::add);
        AgentToolEventBus.unregister("tr-test");

        AgentToolEventBus.publish("tr-test", AgentToolEvent.done());

        assertTrue(received.isEmpty());
    }
}
