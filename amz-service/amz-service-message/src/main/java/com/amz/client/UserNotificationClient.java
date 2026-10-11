package com.amz.client;

import com.amz.client.fallback.UserNotificationClientFallbackFactory;
import com.amz.result.Result;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.Map;

/**
 * 站内通知落库客户端（message → user）。
 * <p>
 * <b>存在动因</b>：站内通知此前只走 WebSocket 推送，用户不在线即丢、无历史——
 * 「库存预警/订单异常」错过就是事故。本客户端在推送时同步落库到 user 服务
 * （通知按用户归属，amz_user 库持有该表；本服务刻意无数据库）。
 * <p>
 * <b>降级</b>：user 不可用时返回失败，调用方记 warn 继续——落库是旁路，
 * 不能因为落库挂掉而阻断推送；但绝不能返回成功假装已落库。
 */
@Component
@FeignClient(name = "amz-service-user", contextId = "userNotificationClient",
        path = "/user/notification",
        fallbackFactory = UserNotificationClientFallbackFactory.class)
public interface UserNotificationClient {

    /**
     * 落库一条通知。
     *
     * @param notification userId / type / title / content
     */
    @PostMapping
    Result<Boolean> save(@RequestBody Map<String, Object> notification);
}
