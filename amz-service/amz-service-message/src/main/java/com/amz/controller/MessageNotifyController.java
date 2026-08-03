package com.amz.controller;

import com.amz.result.Result;
import com.amz.session.Session;
import io.netty.channel.Channel;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 消息通知 REST 控制器（<b>纯服务间内部端点</b>）。
 * <p>
 * 暴露 HTTP 端点供其他微服务（如 amz-service-ai 的 DailyReportScheduler）
 * 通过 Feign 调用，将业务消息推送到用户消息中心。
 * <p>
 * 路径前缀固定为 {@code /internal}：该前缀已在 {@code BaseAuthInterceptor} 白名单中放行，
 * 且网关未配置对应路由、不对外暴露。这样调用方即便运行在无请求上下文的定时任务线程
 * （无用户 JWT 可透传）也能正常调用，不会被鉴权拦截器 401 拒绝。
 * <p>
 * 内部使用 {@link Session} 绑定的 Netty Channel 通过 WebSocket 推送文本消息。
 * 用户未在线时记录 warn 日志并返回 success（不阻断调用方流程）。
 */
@Slf4j
@RestController
@RequestMapping("/internal/message")
public class MessageNotifyController {

    /**
     * 推送通知到指定用户的 WebSocket 通道。
     * <p>
     * POST /internal/message/notify
     * <p>
     * 调用方应在请求体中提供 userId、消息类型（type）与消息内容（content）。
     * 服务端将 content 文本通过 WebSocket 推送给目标用户；
     * 用户不在线时返回 success（不阻断调度），仅记录 warn 日志。
     */
    @PostMapping("/notify")
    public Result<NotifyResponse> notify(@RequestBody NotifyRequest req) {
        if (req == null || req.getUserId() == null) {
            return Result.failure("userId 不能为空");
        }
        String content = req.getContent() == null || req.getContent().isEmpty()
                ? "" : req.getContent();
        Integer userId = req.getUserId().intValue();

        Channel channel = Session.getChannel(userId);
        NotifyResponse resp = new NotifyResponse();
        resp.setUserId(userId);
        resp.setDelivered(false);

        if (channel == null || !channel.isActive()) {
            log.warn("用户 {} 不在线，消息未推送（已忽略）：type={}, length={}",
                    userId, req.getType(), content.length());
            resp.setReason("user_offline");
            return Result.success(resp);
        }

        try {
            channel.writeAndFlush(new TextWebSocketFrame(content));
            resp.setDelivered(true);
            log.info("WebSocket 推送成功：userId={}, type={}, length={}",
                    userId, req.getType(), content.length());
            return Result.success(resp);
        } catch (Exception e) {
            log.warn("WebSocket 推送失败：userId={}, type={}, error={}",
                    userId, req.getType(), e.getMessage());
            resp.setReason("push_failed:" + e.getMessage());
            return Result.success(resp);
        }
    }

    /** 推送请求体。 */
    @Data
    public static class NotifyRequest {
        /** 目标用户 ID。 */
        private Long userId;
        /** 消息类型（参考 MessageTypeEnum，可不传）。 */
        private Integer type;
        /** 推送内容（文本）。 */
        private String content;
    }

    /** 推送响应体。 */
    @Data
    public static class NotifyResponse {
        /** 接收用户 ID。 */
        private Integer userId;
        /** 是否成功投递到 WebSocket。 */
        private boolean delivered;
        /** 未投递原因（delivered=false 时填充）。 */
        private String reason;
    }
}
