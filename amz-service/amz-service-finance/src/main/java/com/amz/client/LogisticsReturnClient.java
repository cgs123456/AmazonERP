package com.amz.client;

import com.amz.client.fallback.LogisticsReturnClientFallbackFactory;
import com.amz.result.Result;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * amz-service-logistics 退货件数接口 Feign 客户端（finance 侧）。
 * <p>
 * <b>存在动因</b>：finance 与 logistics 之间此前<b>没有任何 Feign 或 MQ</b>——
 * 退款闭环的「钱」那半条链（结算报表 → 凭证 → 利润减收入）通了，
 * 但「货」那半条链（退货入仓 → 冲减 COGS）没有通道，于是售出成本永远虚高。
 * 本客户端补上这条边：财务算单品利润时按 SKU 取已收货的良品退货件数，用来冲减销货成本。
 * <p>
 * <b>降级策略</b>：logistics 不可用时返回失败（不返回 0）。
 * 返回 0 会让「查不到退货」与「真的没有退货」不可区分，利润因此偏高——
 * 与 {@code ProcurementCostClient} 同一条「不静默降级」纪律。
 */
@FeignClient(name = "amz-service-logistics", contextId = "logisticsReturnClient",
        path = "/logistics/return",
        fallbackFactory = LogisticsReturnClientFallbackFactory.class)
public interface LogisticsReturnClient {

    /**
     * 已确认收货的良品退货件数（用于冲减 COGS）。
     *
     * @param shopId 店铺
     * @param sku    卖家 SKU
     * @return 良品退货件数（RECEIVED/CLOSED 且 disposition=GOOD 的合计）
     */
    @GetMapping("/returned-units/{shopId}")
    Result<Integer> returnedGoodUnits(@PathVariable("shopId") Long shopId,
                                      @RequestParam("sku") String sku);
}
