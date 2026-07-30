package com.amz.client.feign.fallback;

import com.amz.client.feign.AdServiceFeignClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.Map;

@Slf4j
@Component
public class AdServiceFeignClientFallbackFactory implements FallbackFactory<AdServiceFeignClient> {

    @Override
    public AdServiceFeignClient create(Throwable cause) {
        log.warn("Feign call to amz-service-ad (report) degraded: cause={}", cause.getMessage());
        return new AdServiceFeignClient() {
            @Override
            public Map<String, Object> getShopSummary(Long shopId) {
                return Collections.emptyMap();
            }
        };
    }
}