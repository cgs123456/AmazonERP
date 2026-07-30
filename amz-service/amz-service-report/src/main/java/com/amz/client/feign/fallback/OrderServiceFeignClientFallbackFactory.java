package com.amz.client.feign.fallback;

import com.amz.client.feign.OrderServiceFeignClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.Map;

@Slf4j
@Component
public class OrderServiceFeignClientFallbackFactory implements FallbackFactory<OrderServiceFeignClient> {

    @Override
    public OrderServiceFeignClient create(Throwable cause) {
        log.warn("Feign call to amz-service-order (report) degraded: cause={}", cause.getMessage());
        return new OrderServiceFeignClient() {
            @Override
            public Map<String, Object> getOrderList() {
                return Collections.emptyMap();
            }

            @Override
            public Map<String, Object> getProfitSummary(Long shopId) {
                return Collections.emptyMap();
            }

            @Override
            public Map<String, Object> getProfitReport(Long shopId, String startDate, String endDate) {
                return Collections.emptyMap();
            }

            @Override
            public Map<String, Object> listOrders(Long shopId, Integer days) {
                return Collections.emptyMap();
            }
        };
    }
}