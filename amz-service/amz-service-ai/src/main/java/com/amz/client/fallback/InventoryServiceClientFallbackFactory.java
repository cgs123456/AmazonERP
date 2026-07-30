package com.amz.client.fallback;

import com.amz.client.InventoryServiceClient;
import com.amz.result.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class InventoryServiceClientFallbackFactory implements FallbackFactory<InventoryServiceClient> {

    @Override
    public InventoryServiceClient create(Throwable cause) {
        log.warn("Feign call to amz-service-spapi degraded: cause={}", cause.getMessage());
        return new InventoryServiceClient() {
            @Override
            public Result<List<Map<String, Object>>> getInventory(Long shopId) {
                return Result.failure("inventory service degraded: " + cause.getMessage());
            }

            @Override
            public Result<List<Map<String, Object>>> getReplenishSuggest(Long shopId, String sku) {
                return Result.failure("inventory service degraded: " + cause.getMessage());
            }

            @Override
            public Result<List<Map<String, Object>>> getInventoryHealth(Long shopId) {
                return Result.failure("inventory service degraded: " + cause.getMessage());
            }
        };
    }
}