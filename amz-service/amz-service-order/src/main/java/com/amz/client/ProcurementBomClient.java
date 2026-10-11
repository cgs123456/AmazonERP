package com.amz.client;

import com.amz.client.fallback.ProcurementBomClientFallbackFactory;
import com.amz.result.Result;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.List;
import java.util.Map;

/**
 * BOM 展开客户端（order → procurement）。
 * <p>
 * <b>存在动因</b>：组合装售出 1 个组合 SKU，实际出库的是 N 个子品；
 * 本地仓按子品备货，组合 SKU 没有库存行。配货出库前必须先展开，
 * 否则库存检查/扣减都会查一个不存在的组合 SKU 行。
 * <p>
 * <b>降级</b>：返回失败，配货编排中止（订单保持未配货，可重试）。
 * 绝不把「展开失败」当成「无 BOM 的普通 SKU」继续扣减——那会按组合 SKU
 * 去扣本地仓，扣一个不存在的行。
 */
@Component
@FeignClient(name = "amz-service-procurement", contextId = "procurementBomClient",
        path = "/procurement/bom",
        fallbackFactory = ProcurementBomClientFallbackFactory.class)
public interface ProcurementBomClient {

    /**
     * 递归展开需求（含环检测）。
     *
     * @param shopId   店铺
     * @param requests SKU×数量需求
     * @return 展开后的子品需求（叶子聚合）
     */
    @PostMapping("/expand/{shopId}")
    Result<List<Map<String, Object>>> expand(@PathVariable("shopId") Long shopId,
                                             @RequestBody List<Map<String, Object>> requests);
}
