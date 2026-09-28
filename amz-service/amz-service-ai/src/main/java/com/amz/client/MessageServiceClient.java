package com.amz.client;

import com.amz.result.Result;
import com.amz.client.fallback.MessageServiceClientFallbackFactory;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.Map;

/**
 * 消息微服务 Feign 客户端。
 * <p>
 * 通过 Nacos 服务名 {@code amz-service-message} 调用，用于推送业务通知到用户消息中心。
 * 该接口不承载 Amazon Messaging API：Amazon 消息动作统一由
 * {@code amz-service-spapi} 的 {@code /spapi/messaging/**} 提供。
 */
@FeignClient(name = "amz-service-message", contextId = "messageServiceClient", fallbackFactory = MessageServiceClientFallbackFactory.class)
public interface MessageServiceClient {

    /**
     * 推送通知到指定用户。
     * 对应 POST /internal/message/notify
     * <p>
     * 走 {@code /internal} 前缀：该端点由 DailyReportScheduler 在定时任务线程中调用，
     * 无用户 JWT 可透传；FeignAuthRelayConfig 会自动附加短时内部服务令牌，下游按 {@code @InternalServiceAccess} 白名单校验调用方服务身份。
     * <p>
     * 请求体字段：{@code userId / type / content}
     */
    @PostMapping("/internal/message/notify")
    Result<Map<String, Object>> notify(@RequestBody Map<String, Object> request);
}
