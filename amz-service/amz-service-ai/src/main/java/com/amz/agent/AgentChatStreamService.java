package com.amz.agent;

import com.amz.agent.langchain4j.LangChain4jAgentService;
import com.amz.result.Result;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Agent SSE 流式服务：把阻塞式 Agent 调用桥接到 Server-Sent Events。
 * <p>
 * 原理：LangChain4j AiServices 在<b>调用线程同步执行</b>工具，因此工作线程先绑定
 * traceId 再调阻塞接口，工具执行点的事件钩子自动按 traceId 路由回本流，无需
 * 改造编排逻辑。事件序列：round_started → tool_call/tool_result（循环）→
 * final → done；异常走 error。旧 POST 端点不受任何影响。
 */
@Slf4j
@Service
public class AgentChatStreamService {

    /** SSE emitter 超时：覆盖模型 60s 超时 + 多轮工具时间 */
    private static final long EMITTER_TIMEOUT_MS = 120_000L;

    private final ExecutorService workers;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private LangChain4jAgentService agentService;

    public AgentChatStreamService() {
        this.workers = Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r, "agent-stream");
            t.setDaemon(true);
            return t;
        });
    }

    @PreDestroy
    public void shutdown() {
        workers.shutdownNow();
    }

    /**
     * 启动一次流式对话。调用方（Controller）负责创建 emitter 并返回，
     * 本方法只负责接线与任务提交，立即返回。
     */
    public void streamChat(Long userId, String message, SseEmitter emitter) {
        String traceId = "tr-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        // unregister 天然幂等，多处清理点可重复调用
        Runnable cleanup = () -> AgentToolEventBus.unregister(traceId);

        AgentToolEventBus.register(traceId, event -> sendEvent(emitter, event, cleanup));
        sendEvent(emitter, AgentToolEvent.roundStarted(traceId), cleanup);

        Future<?> future = workers.submit(() -> {
            AgentTraceContext.set(traceId);
            try {
                Result<String> result = agentService.chat(userId, message);
                if (result != null && result.getCode() == 200 && result.getData() != null) {
                    sendEvent(emitter, AgentToolEvent.finalAnswer(result.getData()), cleanup);
                } else {
                    sendEvent(emitter, AgentToolEvent.error(
                            result == null ? "Agent 调用失败" : result.getMessage()), cleanup);
                }
            } catch (Exception e) {
                log.warn("Agent 流式对话异常 traceId={}", traceId, e);
                sendEvent(emitter, AgentToolEvent.error("Agent 调用失败，请稍后重试"), cleanup);
            } finally {
                AgentTraceContext.clear();
                sendEvent(emitter, AgentToolEvent.done(), cleanup);
                try {
                    emitter.complete();
                } catch (Exception e) {
                    log.debug("SSE emitter 关闭异常 traceId={}", traceId, e);
                }
                cleanup.run();
            }
        });

        emitter.onCompletion(cleanup);
        emitter.onTimeout(() -> {
            future.cancel(true);
            sendEvent(emitter, AgentToolEvent.error("对话超时，请稍后重试"), cleanup);
            cleanup.run();
        });
        emitter.onError(e -> {
            future.cancel(true);
            cleanup.run();
        });
    }

    private void sendEvent(SseEmitter emitter, AgentToolEvent event, Runnable cleanup) {
        try {
            String json = objectMapper.writeValueAsString(event);
            emitter.send(SseEmitter.event().name(event.getType()).data(json));
        } catch (IOException e) {
            // 客户端已断开：清理监听，后续事件自动静默（bus 无监听器时跳过）
            log.debug("SSE 发送失败，清理监听 type={}", event.getType(), e);
            cleanup.run();
        } catch (Exception e) {
            log.warn("SSE 事件序列化失败 type={}", event.getType(), e);
        }
    }
}
