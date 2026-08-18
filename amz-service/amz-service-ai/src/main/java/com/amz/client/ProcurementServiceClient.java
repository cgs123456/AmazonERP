package com.amz.client;

import com.amz.result.Result;
import com.amz.client.fallback.ProcurementServiceClientFallbackFactory;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;
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

    /**
     * 查询采购订单列表（真实调用 amz-service-procurement 的 /procurement/order/list/{shopId}）。
     * 返回 List&lt;Map&gt; 以复用各服务模块自身的数据模型，避免跨模块耦合。
     */
    @GetMapping("/procurement/order/list/{shopId}")
    Result<List<Map<String, Object>>> getPurchaseOrders(@PathVariable("shopId") Long shopId);

    /**
     * 查询供应商列表（真实调用 amz-service-procurement 的 /procurement/supplier/list/{shopId}）。
     */
    @GetMapping("/procurement/supplier/list/{shopId}")
    Result<List<Map<String, Object>>> getSuppliers(@PathVariable("shopId") Long shopId,
                                                   @RequestParam(value = "status", required = false) String status);

    /**
     * 多供应商比价（按 SKU 查询多家供应商报价，含评分/交期/最低单价）。
     * 对应 amz-service-procurement 的 /procurement/supplier/compare/{shopId}?sku=xxx。
     */
    @GetMapping("/procurement/supplier/compare/{shopId}")
    Result<List<Map<String, Object>>> compareSupplierPrices(@PathVariable("shopId") Long shopId,
                                                             @RequestParam String sku);
}
