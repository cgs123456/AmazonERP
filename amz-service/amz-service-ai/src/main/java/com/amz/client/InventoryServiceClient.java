package com.amz.client;

import com.amz.result.Result;
import com.amz.client.fallback.InventoryServiceClientFallbackFactory;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;
import java.util.Map;

/**
 * SP-API 库存微服务 Feign 客户端。
 * 用于 Agent 工具调用 FBA 库存查询、补货建议、库存健康度。
 */
@FeignClient(name = "amz-service-spapi", contextId = "inventoryServiceClient", fallbackFactory = InventoryServiceClientFallbackFactory.class)
public interface InventoryServiceClient {

    /**
     * 查询指定店铺的 FBA 库存列表。
     */
    @GetMapping("/spapi/inventory/{shopId}")
    Result<List<Map<String, Object>>> getInventory(@PathVariable("shopId") Long shopId);

    /**
     * 查询补货建议（按 shopId + sku 过滤）。
     */
    @GetMapping("/spapi/replenish/suggest")
    Result<List<Map<String, Object>>> getReplenishSuggest(@RequestParam("shopId") Long shopId,
                                                           @RequestParam(value = "sku", required = false) String sku);

    /**
     * 查询库存健康度分布。
     */
    @GetMapping("/spapi/inventory/health")
    Result<List<Map<String, Object>>> getInventoryHealth(@RequestParam("shopId") Long shopId);
}
