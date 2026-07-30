package com.amz.client.fallback;

import com.amz.client.ProductServiceClient;
import com.amz.result.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.stereotype.Component;

import java.util.Map;

@Slf4j
@Component
public class ProductServiceClientFallbackFactory implements FallbackFactory<ProductServiceClient> {

    @Override
    public ProductServiceClient create(Throwable cause) {
        log.warn("Feign call to amz-service-product degraded: cause={}", cause.getMessage());
        return new ProductServiceClient() {
            @Override
            public Result<Map<String, Object>> copyListing(Map<String, Object> request) {
                return Result.failure("product service degraded: " + cause.getMessage());
            }

            @Override
            public Result<Map<String, Object>> translate(Map<String, Object> request) {
                return Result.failure("product service degraded: " + cause.getMessage());
            }

            @Override
            public Result<Map<String, Object>> estimateFbaFees(String asin, Double price, Double weight, String sizeTier) {
                return Result.failure("product service degraded: " + cause.getMessage());
            }
        };
    }
}