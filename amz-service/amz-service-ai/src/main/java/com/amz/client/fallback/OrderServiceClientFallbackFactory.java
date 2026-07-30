package com.amz.client.fallback;

import com.amz.client.OrderServiceClient;
import com.amz.result.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.stereotype.Component;

import java.util.Map;

@Slf4j
@Component
public class OrderServiceClientFallbackFactory implements FallbackFactory<OrderServiceClient> {

    @Override
    public OrderServiceClient create(Throwable cause) {
        log.warn("Feign call to amz-service-order degraded: cause={}", cause.getMessage());
        return new OrderServiceClient() {
            @Override
            public Result<Map<String, Object>> getOrderList(Long shopId, Integer days) {
                return Result.failure("order service degraded: " + cause.getMessage());
            }

            @Override
            public Result<Map<String, Object>> getProfitReport(Long shopId, String startDate, String endDate) {
                return Result.failure("order service degraded: " + cause.getMessage());
            }
        };
    }
}