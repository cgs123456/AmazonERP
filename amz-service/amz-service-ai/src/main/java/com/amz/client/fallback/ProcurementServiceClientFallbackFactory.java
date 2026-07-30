package com.amz.client.fallback;

import com.amz.client.ProcurementServiceClient;
import com.amz.result.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.stereotype.Component;

import java.util.Map;

@Slf4j
@Component
public class ProcurementServiceClientFallbackFactory implements FallbackFactory<ProcurementServiceClient> {

    @Override
    public ProcurementServiceClient create(Throwable cause) {
        log.warn("Feign call to amz-service-procurement degraded: cause={}", cause.getMessage());
        return new ProcurementServiceClient() {
            @Override
            public Result<Map<String, Object>> getPromotionPlan(Long shopId, String asin, String goal) {
                return Result.failure("procurement service degraded: " + cause.getMessage());
            }
        };
    }
}