package com.amz.client.feign.fallback;

import com.amz.client.feign.FinanceServiceFeignClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.Map;

@Slf4j
@Component
public class FinanceServiceFeignClientFallbackFactory implements FallbackFactory<FinanceServiceFeignClient> {

    @Override
    public FinanceServiceFeignClient create(Throwable cause) {
        log.warn("Feign call to amz-service-finance degraded: cause={}", cause.getMessage());
        return new FinanceServiceFeignClient() {
            @Override
            public Map<String, Object> calculateProfit(Long shopId, String startDate, String endDate) {
                return Collections.emptyMap();
            }
        };
    }
}