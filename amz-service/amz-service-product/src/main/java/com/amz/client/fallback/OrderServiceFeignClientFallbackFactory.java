package com.amz.client.fallback;

import com.amz.client.OrderServiceFeignClient;
import com.amz.result.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.stereotype.Component;

import java.util.Map;

@Slf4j
@Component
public class OrderServiceFeignClientFallbackFactory implements FallbackFactory<OrderServiceFeignClient> {

    @Override
    public OrderServiceFeignClient create(Throwable cause) {
        log.warn("Feign call to amz-service-order (product) degraded: cause={}", cause.getMessage());
        return new OrderServiceFeignClient() {
            @Override
            public Result<Map<String, Object>> lookupFbaFees(String sizeTier, Integer weight) {
                return Result.failure("order service degraded: " + cause.getMessage());
            }
        };
    }
}