package com.amz.client.fallback;

import com.amz.client.UserNotificationClient;
import com.amz.result.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 通知落库降级工厂。
 * <p>
 * 返回失败（不伪造成功）：落库是旁路，调用方记 warn 后继续推送；
 * 但「假装落库成功」会让历史静默丢条目，与不落库无异且更难排查。
 */
@Slf4j
@Component
public class UserNotificationClientFallbackFactory implements FallbackFactory<UserNotificationClient> {

    @Override
    public UserNotificationClient create(Throwable cause) {
        log.warn("Feign call to amz-service-user (notification) degraded", cause);
        final String reason = "user service degraded: " + cause.getMessage();
        return new UserNotificationClient() {
            @Override
            public Result<Boolean> save(Map<String, Object> notification) {
                return Result.failure(reason);
            }
        };
    }
}
