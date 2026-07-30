package com.amz.client.feign;

import com.amz.client.feign.fallback.OrderServiceFeignClientFallbackFactory;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.Map;

/**
 * 订单微服务 Feign 客户端。
 * <p>
 * 通过 Nacos 服务名 {@code amz-service-order} 调用，用于报表聚合。
 * 返回类型使用 {@code Map} 避免跨服务 DTO 依赖。
 */
@FeignClient(name = "amz-service-order", contextId = "orderFeignClient", fallbackFactory = OrderServiceFeignClientFallbackFactory.class)
public interface OrderServiceFeignClient {

    /**
     * 获取店铺订单列表。
     * 对应 GET /order/getOrderList
     */
    @GetMapping("/order/getOrderList")
    Map<String, Object> getOrderList();

    /**
     * 获取店铺月度利润汇总（含销售额、订单数等）。
     * 对应 GET /profit/summary/{shopId}
     */
    @GetMapping("/profit/summary/{shopId}")
    Map<String, Object> getProfitSummary(@PathVariable("shopId") Long shopId);

    /**
     * 查询指定店铺利润报告（按日期范围聚合，含每日 revenue/net_profit 等明细）。
     * 对应 GET /order/profit/report
     * <p>
     * 用于销售额趋势（按日聚合）。
     */
    @GetMapping("/order/profit/report")
    Map<String, Object> getProfitReport(@RequestParam("shopId") Long shopId,
                                        @RequestParam("startDate") String startDate,
                                        @RequestParam("endDate") String endDate);

    /**
     * 查询指定店铺最近 N 天订单汇总（含 orders 明细）。
     * 对应 GET /order/list
     * <p>
     * 用于店铺销售额分布（按 shopId 聚合）。
     */
    @GetMapping("/order/list")
    Map<String, Object> listOrders(@RequestParam("shopId") Long shopId,
                                   @RequestParam(value = "days", defaultValue = "30") Integer days);
}
