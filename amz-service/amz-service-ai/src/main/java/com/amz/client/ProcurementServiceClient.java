package com.amz.client;

import com.amz.result.Result;
import com.amz.client.fallback.ProcurementServiceClientFallbackFactory;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.Map;

/**
 * 采购微服务 Feign 客户端。
 * 用于 Agent 工具调用促销方案生成。
 */
@FeignClient(name = "amz-service-procurement", contextId = "procurementServiceClient", fallbackFactory = ProcurementServiceClientFallbackFactory.class)
public interface ProcurementServiceClient {

    /**
     * 查询促销计划。
     */
    @GetMapping("/procurement/promotion/plan")
    Result<Map<String, Object>> getPromotionPlan(@RequestParam("shopId") Long shopId,
                                                 @RequestParam(value = "asin", required = false) String asin,
                                                 @RequestParam(value = "goal", required = false) String goal);
}
