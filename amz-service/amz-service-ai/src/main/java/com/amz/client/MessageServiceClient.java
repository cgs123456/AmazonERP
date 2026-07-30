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
 * 失败时由调用方降级处理（仅 warn 日志），不阻断业务流程。
 */
@FeignClient(name = "amz-service-message", contextId = "messageServiceClient", fallbackFactory = MessageServiceClientFallbackFactory.class)
public interface MessageServiceClient {

    /**
     * 推送通知到指定用户。
     * 对应 POST /message/notify
     * <p>
     * 请求体字段：{@code userId / type / content}
     */
    @PostMapping("/message/notify")
    Result<Map<String, Object>> notify(@RequestBody Map<String, Object> request);
}
