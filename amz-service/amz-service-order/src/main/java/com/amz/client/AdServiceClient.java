package com.amz.client;

import com.amz.result.Result;
import com.amz.client.fallback.AdServiceClientFallbackFactory;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.util.Map;

/**
 * 广告服务 Feign 客户端（利润计算接入广告花费）。
 * <p>
 * 返回类型用 {@code Map} 避免跨模块依赖 amz-service-ad 的 AdReport。
 * 对应 AdController#getSummary，返回的 Map 含 cost（广告花费）等字段。
 */
@Component
@FeignClient(name = "amz-service-ad", fallbackFactory = AdServiceClientFallbackFactory.class)
public interface AdServiceClient {

    /**
     * 查询店铺整体广告汇总指标。
     *
     * @param shopId 店铺 ID
     * @return 广告汇总（含 cost 广告花费字段）
     */
    @GetMapping("/ad/summary/{shopId}")
    Result<Map<String, Object>> getShopSummary(@PathVariable("shopId") Long shopId);
}