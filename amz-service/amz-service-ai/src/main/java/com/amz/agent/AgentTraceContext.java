package com.amz.agent;

/**
 * Agent 执行链路追踪 ID 上下文（ThreadLocal）。
 * <p>
 * 用途：SSE 流式端点把一次 Agent 对话绑定到 traceId，工作线程在调用阻塞式
 * Agent 之前 set，工具执行点（{@code ErpToolExecutor}）据此发布事件到
 * {@link AgentToolEventBus}，前端按 traceId 收到该轮的 tool_call/tool_result。
 * <p>
 * 生效前提：LangChain4j AiServices 在<b>调用线程同步执行</b>工具（各版本皆如此），
 * 无需跨线程透传；工作线程结束前必须 {@code clear()}，线程池复用时防串话。
 */
public final class AgentTraceContext {

    private static final ThreadLocal<String> TRACE = new ThreadLocal<>();

    private AgentTraceContext() {
    }

    public static void set(String traceId) {
        TRACE.set(traceId);
    }

    public static String get() {
        return TRACE.get();
    }

    public static void clear() {
        TRACE.remove();
    }
}
