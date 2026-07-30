package com.amz.client.fallback;

import com.amz.client.AdServiceClient;
import com.amz.result.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.stereotype.Component;

import java.util.Map;

@Slf4j
@Component
public class AdServiceClientFallbackFactory implements FallbackFactory<AdServiceClient> {

    @Override
    public AdServiceClient create(Throwable cause) {
        log.warn("Feign call to amz-service-ad (order) degraded: cause={}", cause.getMessage());
        return new AdServiceClient() {
            @Override
            public Result<Map<String, Object>> getShopSummary(Long shopId) {
                return Result.failure("ad service degraded: " + cause.getMessage());
            }
        };
    }
}