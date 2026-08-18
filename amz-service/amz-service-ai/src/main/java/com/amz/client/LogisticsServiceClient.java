package com.amz.client;

import com.amz.client.fallback.LogisticsServiceClientFallbackFactory;
import com.amz.result.Result;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;
import java.util.Map;

/**
 * 物流微服务 Feign 客户端。
 * 用于 Agent 工具调用真实物流轨迹查询。
 */
@FeignClient(name = "amz-service-logistics", contextId = "logisticsServiceClient",
        fallbackFactory = LogisticsServiceClientFallbackFactory.class)
public interface LogisticsServiceClient {

    /**
     * 查询货件完整轨迹（真实调用 amz-service-logistics 的 /logistics/shipment/{shipmentId}/tracking）。
     * 返回 List&lt;Map&gt; 以复用物流模块自身的数据模型，避免跨模块耦合。
     */
    @GetMapping("/logistics/shipment/{shipmentId}/tracking")
    Result<List<Map<String, Object>>> getTracking(@PathVariable("shipmentId") Long shipmentId);

    /**
     * 查询店铺货件列表（用于按 shipmentNo 反查 shipmentId）。
     */
    @GetMapping("/logistics/shipment/list/{shopId}")
    Result<List<Map<String, Object>>> listShipments(@PathVariable("shopId") Long shopId,
                                                    @RequestParam(value = "status", required = false) String status);

    /**
     * 多仓库存全局视图（按仓库类型聚合，含库存分布/库龄/缺货项）。
     * 对应 amz-service-logistics 的 /logistics/warehouse/stock/view/{shopId}。
     */
    @GetMapping("/logistics/warehouse/stock/view/{shopId}")
    Result<Map<String, Object>> getGlobalInventoryView(@PathVariable("shopId") Long shopId);

    /**
     * 多仓库存老化分析（含各仓库 SKU 入库天数、滞销预警）。
     * 对应 amz-service-logistics 的 /logistics/warehouse/stock/aging/{shopId}。
     */
    @GetMapping("/logistics/warehouse/stock/aging/{shopId}")
    Result<Map<String, Object>> getAgingAnalysis(@PathVariable("shopId") Long shopId);

    /**
     * 物流商运费比价（最优/次优/第三路线路 + 费用 + 时效）。
     * 对应 amz-service-logistics 的 /logistics/v2/quote/compare/{shopId}。
     */
    @GetMapping("/logistics/v2/quote/compare/{shopId}")
    Result<Map<String, Object>> compareShippingQuotes(@PathVariable("shopId") Long shopId,
                                                       @RequestParam String originPort,
                                                       @RequestParam String destinationPort,
                                                       @RequestParam(value = "weightKg", required = false) Double weightKg,
                                                       @RequestParam(value = "volumeCbm", required = false) Double volumeCbm);
}
