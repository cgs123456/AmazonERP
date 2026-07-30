package com.amz.client.fallback;

import com.amz.client.AiServiceClient;
import com.amz.result.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.stereotype.Component;

import java.util.Map;

@Slf4j
@Component
public class AiServiceClientFallbackFactory implements FallbackFactory<AiServiceClient> {

    @Override
    public AiServiceClient create(Throwable cause) {
        log.warn("Feign call to amz-service-ai degraded: cause={}", cause.getMessage());
        return new AiServiceClient() {
            @Override
            public Result<Map<String, Object>> analyzeSelection(Map<String, Object> opportunity) {
                return Result.failure("ai service degraded: " + cause.getMessage());
            }
        };
    }
}