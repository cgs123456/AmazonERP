package com.amz.controller;

import com.amz.agent.AgentChatStreamService;
import com.amz.context.UserContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Agent SSE 流式端点（A-1）。
 * <p>
 * GET /ai/chat-stream?message=... → text/event-stream：
 * round_started → tool_call/tool_result（循环）→ final → done，异常走 error。
 * <p>
 * 与现有 POST /ai/erp/agent 并存：旧端点与既有 E2E/单测不受影响，前端渐进迁移、
 * 失败回退旧链路。身份只从 JWT 认证上下文读取，不接受查询参数覆盖。
 */
@RestController
@RequestMapping("/ai")
public class AgentSseController {

    /** 与流式服务侧 emitter 超时对齐 */
    private static final long EMITTER_TIMEOUT_MS = 120_000L;

    @Autowired
    private AgentChatStreamService streamService;

    @GetMapping(value = "/chat-stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatStream(@RequestParam(value = "message", required = false) String message) {
        requireAuthenticatedUser();
        SseEmitter emitter = new SseEmitter(EMITTER_TIMEOUT_MS);
        if (message == null || message.isBlank()) {
            try {
                emitter.send(SseEmitter.event().name("error").data("{\"message\":\"message 不能为空\"}"));
                emitter.complete();
            } catch (Exception e) {
                emitter.completeWithError(e);
            }
            return emitter;
        }
        if (message.length() > 2000) {
            try {
                emitter.send(SseEmitter.event().name("error").data("{\"message\":\"message 长度不能超过2000字符\"}"));
                emitter.complete();
            } catch (Exception e) {
                emitter.completeWithError(e);
            }
            return emitter;
        }
        streamService.streamChat(message, emitter);
        return emitter;
    }

    private void requireAuthenticatedUser() {
        if (UserContext.getUserId() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "未登录");
        }
    }
}
