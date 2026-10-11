package com.amz.client;

import com.amz.client.fallback.LogisticsFulfillmentClientFallbackFactory;
import com.amz.result.Result;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.List;
import java.util.Map;

/**
 * 履约支撑 Feign 客户端（order → logistics）。
 * <p>
 * <b>存在动因</b>：配货履约流需要「本地仓库存检查 + 出库执行」两个动作，
 * 库存账在 logistics 域——这是 order→logistics 的第二条通道（第一条是
 * finance→logistics 的退货件数）。出库执行复用既有出库状态机（含扣库存与流水）。
 * <p>
 * <b>降级</b>：返回失败，调用方不推进任何状态（订单保持未配货），可重试。
 */
@Component
@FeignClient(name = "amz-service-logistics", contextId = "logisticsFulfillmentClient",
        path = "/logistics/fulfillment",
        fallbackFactory = LogisticsFulfillmentClientFallbackFactory.class)
public interface LogisticsFulfillmentClient {

    /**
     * 库存可用性检查（不扣减）。
     *
     * @param request shopId / warehouseId / items[{sku, quantity}]
     * @return 逐 SKU 可用性（sufficient=false 即不足）
     */
    @PostMapping("/check")
    Result<List<Map<String, Object>>> checkStock(@RequestBody Map<String, Object> request);

    /**
     * 出库执行（建单 + 发运 + 扣库存 + 写流水，整体事务）。
     *
     * @param request shopId / warehouseId / referenceNo(Amazon 订单号) / items
     * @return 出库单号
     */
    @PostMapping("/outbound")
    Result<Map<String, Object>> executeOutbound(@RequestBody Map<String, Object> request);
}
