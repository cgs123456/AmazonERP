package com.amz.client.fallback;

import com.amz.client.AdServiceClient;
import com.amz.result.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class AdServiceClientFallbackFactory implements FallbackFactory<AdServiceClient> {

    @Override
    public AdServiceClient create(Throwable cause) {
        log.warn("Feign call to amz-service-ad degraded: cause={}", cause.getMessage());
        return new AdServiceClient() {
            @Override
            public Result<List<Map<String, Object>>> getReports(Long shopId) {
                return Result.failure("ad service degraded: " + cause.getMessage());
            }

            @Override
            public Result<Map<String, Object>> getCompetitor(Long shopId, String asin) {
                return Result.failure("ad service degraded: " + cause.getMessage());
            }
        };
    }
}