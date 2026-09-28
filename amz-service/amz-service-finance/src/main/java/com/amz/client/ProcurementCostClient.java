package com.amz.client;

import com.amz.client.dto.RemoteBatchCostSummary;
import com.amz.client.fallback.ProcurementCostClientFallbackFactory;
import com.amz.result.Result;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;



/**
 * amz-service-procurement 批次成本接口 Feign 客户端（finance 侧）。
 * <p>
 * 单品真实利润的成本侧需要按 SKU 取批次成本（FIFO 顺序），
 * 而批次数据属采购域 —— finance 只消费，不另起一套成本口径。
 */
@FeignClient(name = "amz-service-procurement", contextId = "procurementCostClient",
        fallbackFactory = ProcurementCostClientFallbackFactory.class)
@RequestMapping("/procurement")
public interface ProcurementCostClient {

    /**
     * 查询 SKU 的 ACTIVE 批次成本聚合值。
     *
     * <p>不返回批次明细：财务计算只需要总数量和总成本，
     * 无界拉取明细既浪费网络和内存，也会在公开列表分页后产生静默漏算。</p>
     */
    @GetMapping("/batch/cost-summary/{shopId}")
    Result<RemoteBatchCostSummary> getCostSummary(@PathVariable("shopId") Long shopId,
                                                  @RequestParam("sku") String sku);
}
