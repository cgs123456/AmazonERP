package com.amz.client.feign;

import com.amz.client.feign.fallback.AdServiceFeignClientFallbackFactory;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.util.Map;

/**
 * 广告微服务 Feign 客户端。
 * <p>
 * 通过 Nacos 服务名 {@code amz-service-ad} 调用，用于报表聚合。
 */
@FeignClient(name = "amz-service-ad", contextId = "adFeignClient", fallbackFactory = AdServiceFeignClientFallbackFactory.class)
public interface AdServiceFeignClient {

    /**
     * 获取店铺广告汇总报表（含广告花费、销售额、ACoS 等）。
     * 对应 GET /ad/summary/{shopId}
     */
    @GetMapping("/ad/summary/{shopId}")
    Map<String, Object> getShopSummary(@PathVariable("shopId") Long shopId);
}
