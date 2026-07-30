package com.amz.client.fallback;

import com.amz.client.MessageServiceClient;
import com.amz.result.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.stereotype.Component;

import java.util.Map;

@Slf4j
@Component
public class MessageServiceClientFallbackFactory implements FallbackFactory<MessageServiceClient> {

    @Override
    public MessageServiceClient create(Throwable cause) {
        log.warn("Feign call to amz-service-message degraded: cause={}", cause.getMessage());
        return new MessageServiceClient() {
            @Override
            public Result<Map<String, Object>> notify(Map<String, Object> request) {
                return Result.failure("message service degraded: " + cause.getMessage());
            }
        };
    }
}