package com.amz.client;

import com.amz.result.Result;
import com.amz.client.fallback.OrderServiceClientFallbackFactory;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.Map;

/**
 * 订单微服务 Feign 客户端。
 * 用于 Agent 工具调用订单查询与利润核算。
 */
@FeignClient(name = "amz-service-order", contextId = "orderServiceClient", fallbackFactory = OrderServiceClientFallbackFactory.class)
public interface OrderServiceClient {

    /**
     * 查询最近 N 天订单汇总。
     */
    @GetMapping("/order/list")
    Result<Map<String, Object>> getOrderList(@RequestParam("shopId") Long shopId,
                                             @RequestParam("days") Integer days);

    /**
     * 查询利润报告（含销售额、成本、利润）。
     */
    @GetMapping("/order/profit/report")
    Result<Map<String, Object>> getProfitReport(@RequestParam("shopId") Long shopId,
                                                @RequestParam("startDate") String startDate,
                                                @RequestParam("endDate") String endDate);
}
