package com.amz.client;

import com.amz.client.dto.RemoteInventoryBatch;
import com.amz.client.fallback.ProcurementCostClientFallbackFactory;
import com.amz.result.Result;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

/**
 * amz-service-procurement 批次成本接口 Feign 客户端（finance 侧）。
 * <p>
 * 单品真实利润的成本侧需要按 SKU 取批次成本（FIFO 顺序），
 * 而批次数据属采购域 —— finance 只消费，不另起一套成本口径。
 */
@FeignClient(name = "amz-service-procurement", contextId = "procurementCostClient",
        fallbackFactory = ProcurementCostClientFallbackFactory.class)
public interface ProcurementCostClient {

    /**
     * 查询 SKU 的库存批次（FIFO 顺序）。
     */
    @GetMapping("/batch/list/{shopId}")
    Result<List<RemoteInventoryBatch>> listBatches(@PathVariable("shopId") Long shopId,
                                                   @RequestParam("sku") String sku);
}
