package com.amz.client;

import com.amz.result.Result;
import com.amz.client.fallback.MessageServiceClientFallbackFactory;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;

import java.util.List;
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
     * 对应 POST /internal/message/notify
     * <p>
     * 走 {@code /internal} 前缀：该端点由 DailyReportScheduler 在定时任务线程中调用，
     * 无 HTTP 请求上下文可透传用户 JWT，需依赖下游鉴权白名单放行。
     * <p>
     * 请求体字段：{@code userId / type / content}
     */
    @PostMapping("/internal/message/notify")
    Result<Map<String, Object>> notify(@RequestBody Map<String, Object> request);

    /**
     * 获取指定店铺的本地消息列表。
     * 对应 GET /message/v2/list/{shopId}
     */
    @GetMapping("/message/v2/list/{shopId}")
    Result<List<Map<String, Object>>> listMessages(@PathVariable Long shopId, @RequestParam int page, @RequestParam int pageSize);
}
