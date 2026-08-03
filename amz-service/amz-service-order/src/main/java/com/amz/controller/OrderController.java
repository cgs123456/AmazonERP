package com.amz.controller;

import com.amz.annotation.ShopScoped;
import com.amz.context.UserContext;
import com.amz.mapper.FbaFeeTableMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.amz.mapper.OrderMapper;
import com.amz.mapper.ProfitReportMapper;
import com.amz.model.FbaFeeTable;
import com.amz.model.dto.OrderDto;
import com.amz.model.pojo.Order;
import com.amz.model.ProfitReport;
import com.amz.model.vo.OrderVo;
import com.amz.result.Result;
import com.amz.service.OrderService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/order")
public class OrderController {

    private static final Logger log = LoggerFactory.getLogger(OrderController.class);

    @Autowired
    private OrderService orderService;

    @Autowired
    private OrderMapper orderMapper;

    @Autowired
    private ProfitReportMapper profitReportMapper;

    @Autowired
    private FbaFeeTableMapper fbaFeeTableMapper;

    @PostMapping("/saveOrder")
    public Result<Void> saveOrder(@RequestBody OrderDto orderDto) {
        return orderService.saveOrder(orderDto);
    }

    @GetMapping("/getOrderList")
    public Result<List<Order>> getOrderList() {
        Integer userId = UserContext.getUserId();
        return orderService.getOrderListByUserId(userId);
    }

    /**
     * 查询指定店铺最近 N 天的订单汇总（供 Agent 工具调用）。
     */
    @ShopScoped
    @GetMapping("/list")
    public Result<Map<String, Object>> listOrders(@RequestParam Long shopId,
                                                  @RequestParam(defaultValue = "7") Integer days) {
        if (shopId == null) {
            return Result.failure("shopId must not be null");
        }
        if (days == null || days <= 0) {
            days = 7;
        }
        LocalDateTime since = LocalDateTime.now().minusDays(days);
        List<Order> orders = orderMapper.selectList(new LambdaQueryWrapper<Order>()
                .eq(Order::getShopId, shopId)
                .gt(Order::getPurchaseDate, since)
                .orderByDesc(Order::getPurchaseDate));

        BigDecimal totalAmount = BigDecimal.ZERO;
        for (Order o : orders) {
            if (o.getFinalPrice() != null) {
                totalAmount = totalAmount.add(o.getFinalPrice());
            }
        }
        Map<String, Object> summary = new HashMap<>();
        summary.put("shopId", shopId);
        summary.put("days", days);
        summary.put("count", orders.size());
        summary.put("totalAmount", totalAmount);
        summary.put("orders", orders);
        return Result.success(summary);
    }

    /**
     * 查询指定店铺利润报告（按日期范围聚合，供 Agent 工具调用）。
     */
    @ShopScoped
    @GetMapping("/profit/report")
    public Result<Map<String, Object>> profitReport(@RequestParam Long shopId,
                                                    @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
                                                    @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        if (shopId == null) {
            return Result.failure("shopId must not be null");
        }
        List<ProfitReport> reports = profitReportMapper.selectList(new LambdaQueryWrapper<ProfitReport>()
                .eq(ProfitReport::getShopId, shopId)
                .ge(ProfitReport::getStatDate, startDate)
                .le(ProfitReport::getStatDate, endDate)
                .orderByDesc(ProfitReport::getStatDate));

        BigDecimal totalRevenue = BigDecimal.ZERO;
        BigDecimal totalCost = BigDecimal.ZERO;
        BigDecimal totalProfit = BigDecimal.ZERO;
        for (ProfitReport r : reports) {
            if (r.getRevenue() != null) totalRevenue = totalRevenue.add(r.getRevenue());
            if (r.getProductCost() != null) totalCost = totalCost.add(r.getProductCost());
            if (r.getNetProfit() != null) totalProfit = totalProfit.add(r.getNetProfit());
        }
        BigDecimal margin = totalRevenue.compareTo(BigDecimal.ZERO) > 0
                ? totalProfit.divide(totalRevenue, 4, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;

        Map<String, Object> summary = new HashMap<>();
        summary.put("shopId", shopId);
        summary.put("startDate", startDate.toString());
        summary.put("endDate", endDate.toString());
        summary.put("totalRevenue", totalRevenue);
        summary.put("totalCost", totalCost);
        summary.put("totalProfit", totalProfit);
        summary.put("margin", margin);
        summary.put("reportCount", reports.size());
        summary.put("reports", reports);
        return Result.success(summary);
    }

    /**
     * FBA 费率查询（内部接口，供 product / report 等微服务通过 Feign 调用）。
     * <p>
     * GET /order/fees/lookup?sizeTier=standard&weight=500
     * <p>
     * 匹配规则：按 sizeTier 过滤，并返回 weight_g &gt;= 入参 weight 的最小重量档费率。
     * 当 sizeTier 不存在或无匹配时返回 null data（调用方降级到硬编码默认值）。
     */
    @GetMapping("/fees/lookup")
    public Result<Map<String, Object>> lookupFbaFees(
            @RequestParam(value = "sizeTier", required = false) String sizeTier,
            @RequestParam(value = "weight", required = false) Integer weight) {
        if (sizeTier == null || sizeTier.isBlank()) {
            sizeTier = "standard";
        }
        if (weight == null || weight <= 0) {
            weight = 1;
        }
        LambdaQueryWrapper<FbaFeeTable> qw = new LambdaQueryWrapper<FbaFeeTable>()
                .eq(FbaFeeTable::getSizeTier, sizeTier)
                .ge(FbaFeeTable::getWeightG, weight)
                .orderByAsc(FbaFeeTable::getWeightG)
                .last("LIMIT 1");
        FbaFeeTable hit = fbaFeeTableMapper.selectOne(qw);
        if (hit == null) {
            // 降级：忽略 weight 重试一次
            hit = fbaFeeTableMapper.selectOne(new LambdaQueryWrapper<FbaFeeTable>()
                    .eq(FbaFeeTable::getSizeTier, sizeTier)
                    .orderByAsc(FbaFeeTable::getWeightG)
                    .last("LIMIT 1"));
        }
        if (hit == null) {
            return Result.success(null);
        }
        Map<String, Object> data = new HashMap<>();
        data.put("sizeTier", hit.getSizeTier());
        data.put("weightG", hit.getWeightG());
        data.put("region", hit.getRegion());
        data.put("fulfillmentFee", hit.getFulfillmentFee());
        data.put("storageFeePerMonth", hit.getStorageFeePerMonth());
        return Result.success(data);
    }

    /**
     * 查询订单详情。
     * GET /order/{orderId}
     * <p>
     * 越权防护（IDOR）：订单按 shopId 归属，必须校验目标订单的 shopId 属于当前登录用户
     * 授权店铺，否则返回统一错误，防止任意登录用户遍历全平台订单。
     */
    @GetMapping("/{orderId}")
    public Result<Order> getOrderById(@PathVariable Long orderId) {
        Result<Order> result = orderService.getOrderById(orderId);
        if (result.getCode() == 200 && result.getData() != null) {
            Order order = result.getData();
            if (!UserContext.isShopAllowed(order.getShopId())) {
                log.warn("订单详情越权拦截：orderId={}, orderShopId={}, userId={}",
                        orderId, order.getShopId(), UserContext.getUserId());
                return Result.failure("订单不存在或无权访问");
            }
        }
        return result;
    }
}
