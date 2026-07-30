package com.amz.client;

import com.amz.result.Result;
import com.amz.client.fallback.OrderServiceFeignClientFallbackFactory;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.Map;

/**
 * 订单微服务 Feign 客户端（product 服务侧）。
 * <p>
 * 用于 FBA 费率查询：调用 amz-service-order 的 /order/fees/lookup 接口
 * 查询真实费率表，避免在 product 服务硬编码费率。
 * 调用失败时由调用方降级到本地估算默认值。
 */
@FeignClient(name = "amz-service-order", contextId = "productOrderServiceFeignClient", fallbackFactory = OrderServiceFeignClientFallbackFactory.class)
public interface OrderServiceFeignClient {

    /**
     * FBA 费率查询。
     * 对应 GET /order/fees/lookup?sizeTier=&weight=
     * <p>
     * 响应 data 字段结构：
     * {@code { sizeTier, weightG, region, fulfillmentFee, storageFeePerMonth }}
     * 当订单服务未配置费率表时返回 null data，调用方应降级到默认硬编码值。
     */
    @GetMapping("/order/fees/lookup")
    Result<Map<String, Object>> lookupFbaFees(@RequestParam(value = "sizeTier", required = false) String sizeTier,
                                              @RequestParam(value = "weight", required = false) Integer weight);
}
