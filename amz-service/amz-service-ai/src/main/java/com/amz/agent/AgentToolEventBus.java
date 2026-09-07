package com.amz.agent;

import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Agent 工具事件总线（进程内，按 traceId 路由）。
 * <p>
 * SSE 控制器按 traceId 注册 emitter 适配器；工具执行点发布事件。
 * 静态工具类、无 Spring 依赖，可直接单测。监听器异常被隔离，
 * 绝不影响工具执行主流程。
 */
@Slf4j
public final class AgentToolEventBus {

    private static final ConcurrentHashMap<String, Consumer<AgentToolEvent>> LISTENERS =
            new ConcurrentHashMap<>();

    private AgentToolEventBus() {
    }

    public static void register(String traceId, Consumer<AgentToolEvent> listener) {
        if (traceId == null || listener == null) {
            return;
        }
        LISTENERS.put(traceId, listener);
    }

    public static void unregister(String traceId) {
        if (traceId == null) {
            return;
        }
        LISTENERS.remove(traceId);
    }

    /**
     * 发布事件。无 trace 或无监听器时静默跳过（普通 POST 链路零开销）。
     */
    public static void publish(String traceId, AgentToolEvent event) {
        if (traceId == null || event == null) {
            return;
        }
        Consumer<AgentToolEvent> listener = LISTENERS.get(traceId);
        if (listener == null) {
            return;
        }
        try {
            listener.accept(event);
        } catch (Exception e) {
            log.warn("Agent 工具事件投递失败 traceId={} type={}", traceId, event.getType(), e);
        }
    }

    /** 仅供测试断言的监听器计数。 */
    static int listenerCount() {
        return LISTENERS.size();
    }
}
