package com.amz.agent;

import com.amz.client.OrderServiceClient;
import com.amz.result.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.amz.agent.ErpToolSupport.fail;
import static com.amz.agent.ErpToolSupport.isSuccess;
import static com.amz.agent.ErpToolSupport.ok;
import static com.amz.agent.ErpToolSupport.toDouble;
import static com.amz.agent.ErpToolSupport.toInt;
import static com.amz.agent.ErpToolSupport.toLong;
import static com.amz.agent.ErpToolSupport.toStr;
import java.time.LocalDate;

/**
 * 订单域 Agent 工具（从 ErpToolExecutor 拆出，§S32）。
 * <p>
 * <b>分组依据是依赖边界</b>：本族只依赖 OrderServiceClient，与其它族零互调用
 * （拆分前逐方法核对）。订单域查询（query_orders / query_sales / query_profit）。
 * <p>
 * 工具名与返回结构逐字保留，ErpToolExecutor 的分发改为委托调用。
 */
@Slf4j
public class ErpOrderTools {

    @Autowired(required = false)
    private OrderServiceClient orderServiceClient;

    /**
     * SSE 事件载荷截断，避免大 JSON 撑爆事件帧（无 trace 时调用方已短路，此处纯工具函数）。
     */
    /**
     * 工具 1：查询最近 N 天订单汇总。
     * 通过 Feign 调用 amz-service-order 的 /order/list 端点。
     */
    public String queryOrders(Map<String, Object> args) {
        Long shopId = toLong(args.get("shopId"));
        Integer days = toInt(args.get("days"), 7);

        log.info("工具调用 query_orders shopId={} days={}", shopId, days);
        if (orderServiceClient == null) {
            return fail("订单服务客户端未启用");
        }
        try {
            Result<Map<String, Object>> result = orderServiceClient.getOrderList(shopId, days);
            if (!isSuccess(result)) {
                return fail("订单查询失败: " + (result != null ? result.getMessage() : "无响应"));
            }
            Map<String, Object> data = result.getData();
            int count = toInt(data.get("count"), 0);
            Object totalAmount = data.get("totalAmount");
            return ok(String.format("查询到 %d 个订单，总金额 %s", count, totalAmount != null ? totalAmount : "$0"), data);
        } catch (Exception e) {
            log.error("query_orders Feign 调用失败 shopId={}", shopId, e);
            return fail("订单服务暂时不可用: " + e.getMessage());
        }
    }


    /**
     * 工具 3：查询销售额趋势。
     * 通过 Feign 调用 amz-service-order 的 /order/profit/report 端点（含销售额数据）。
     */
    public String querySales(Map<String, Object> args) {
        Long shopId = toLong(args.get("shopId"));
        String start = toStr(args.get("start"));
        String end = toStr(args.get("end"));
        if (start == null) start = LocalDate.now().minusDays(7).toString();
        if (end == null) end = LocalDate.now().toString();

        log.info("工具调用 query_sales shopId={} start={} end={}", shopId, start, end);
        if (orderServiceClient == null) {
            return fail("订单服务客户端未启用");
        }
        try {
            Result<Map<String, Object>> result = orderServiceClient.getProfitReport(shopId, start, end);
            if (!isSuccess(result)) {
                return fail("销售额查询失败: " + (result != null ? result.getMessage() : "无响应"));
            }
            Map<String, Object> data = result.getData();
            Object totalRevenue = data.get("totalRevenue");
            Object margin = data.get("margin");
            return ok(String.format("销售额 %s，利润率 %s", totalRevenue != null ? totalRevenue : "$0", margin != null ? margin : "0"),
                    data);
        } catch (Exception e) {
            log.error("query_sales Feign 调用失败 shopId={}", shopId, e);
            return fail("订单服务暂时不可用: " + e.getMessage());
        }
    }


    /**
     * 工具 4：查询单品利润。
     * 通过 Feign 调用 amz-service-order 的 /order/profit/report 端点。
     */
    public String queryProfit(Map<String, Object> args) {
        Long shopId = toLong(args.get("shopId"));
        String asin = toStr(args.get("asin"));
        // 默认查最近 30 天
        String start = LocalDate.now().minusDays(30).toString();
        String end = LocalDate.now().toString();

        log.info("工具调用 query_profit shopId={} asin={}", shopId, asin);
        if (orderServiceClient == null) {
            return fail("订单服务客户端未启用");
        }
        try {
            Result<Map<String, Object>> result = orderServiceClient.getProfitReport(shopId, start, end);
            if (!isSuccess(result)) {
                return fail("利润查询失败: " + (result != null ? result.getMessage() : "无响应"));
            }
            Map<String, Object> data = result.getData();
            Object totalRevenue = data.get("totalRevenue");
            Object totalCost = data.get("totalCost");
            Object totalProfit = data.get("totalProfit");
            Object margin = data.get("margin");
            String asinDesc = asin != null ? "ASIN " + asin + " " : "";
            return ok(String.format("%s利润分析（近30天）：收入 %s，成本 %s，利润 %s，利润率 %s",
                            asinDesc, totalRevenue, totalCost, totalProfit, margin), data);
        } catch (Exception e) {
            log.error("query_profit Feign 调用失败 shopId={}", shopId, e);
            return fail("订单服务暂时不可用: " + e.getMessage());
        }
    }
}
