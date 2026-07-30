package com.amz.controller;

import com.amz.annotation.ShopScoped;
import com.amz.model.PurchaseOrder;
import com.amz.model.QualityCheck;
import com.amz.result.Result;
import com.amz.service.ProcurementService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 采购供应链 REST 端点。
 */
@RestController
@RequestMapping("/procurement")
public class ProcurementController {

    @Autowired
    private ProcurementService procurementService;

    /**
     * 创建采购单（草稿）。
     * POST /procurement/order
     */
    @PostMapping("/order")
    public Result<PurchaseOrder> createOrder(@RequestBody PurchaseOrder order) {
        return Result.success(procurementService.createPurchaseOrder(order));
    }

    /**
     * 提交采购单到 1688 平台。
     * POST /procurement/order/{orderId}/submit
     */
    @PostMapping("/order/{orderId}/submit")
    public Result<PurchaseOrder> submitTo1688(@PathVariable Long orderId) {
        return Result.success(procurementService.submitTo1688(orderId));
    }

    /**
     * 同步 1688 订单状态。
     * POST /procurement/order/{orderId}/sync
     */
    @PostMapping("/order/{orderId}/sync")
    public Result<PurchaseOrder> syncStatus(@PathVariable Long orderId) {
        return Result.success(procurementService.syncOrderStatus(orderId));
    }

    /**
     * 取消采购单。
     * POST /procurement/order/{orderId}/cancel
     */
    @PostMapping("/order/{orderId}/cancel")
    public Result<Boolean> cancelOrder(@PathVariable Long orderId) {
        return Result.success(procurementService.cancelPurchaseOrder(orderId));
    }

    /**
     * 查询店铺采购单列表。
     * GET /procurement/order/list/{shopId}
     */
    @ShopScoped
    @GetMapping("/order/list/{shopId}")
    public Result<List<PurchaseOrder>> listOrders(@PathVariable Long shopId) {
        return Result.success(procurementService.listPurchaseOrders(shopId));
    }

    /**
     * 提交质检结果。
     * POST /procurement/qc/{purchaseOrderId}
     */
    @PostMapping("/qc/{purchaseOrderId}")
    public Result<QualityCheck> submitQualityCheck(
            @PathVariable Long purchaseOrderId,
            @RequestParam Integer sampleCount,
            @RequestParam Integer failedCount,
            @RequestParam(required = false) String defectDescription,
            @RequestParam String inspector) {
        return Result.success(procurementService.submitQualityCheck(
                purchaseOrderId, sampleCount, failedCount, defectDescription, inspector));
    }

    /**
     * 查询促销计划（供 Agent 工具调用）。
     * 注：当前为基于促销日历的规则化方案，后续可接入 LLM 生成个性化方案。
     * GET /procurement/promotion/plan?shopId=1&asin=B0xxx&goal=提升销量
     */
    @ShopScoped
    @GetMapping("/promotion/plan")
    public Result<Map<String, Object>> getPromotionPlan(
            @RequestParam Long shopId,
            @RequestParam(required = false) String asin,
            @RequestParam(required = false) String goal) {
        if (shopId == null) {
            return Result.failure("shopId must not be null");
        }
        if (goal == null || goal.isBlank()) goal = "提升销量";

        LocalDate today = LocalDate.now();
        LocalDate start = today.plusDays(1);
        LocalDate end = start.plusDays(2);

        Map<String, Object> plan = new HashMap<>();
        plan.put("promotionType", "Lightning Deal");
        plan.put("discountRate", 0.20);
        plan.put("duration", start.toString() + " 至 " + end.toString() + "（48 小时）");
        plan.put("estimatedSalesUplift", "150%");
        plan.put("budget", "$500 广告预算");
        plan.put("strategy", "Prime 会员定向 8 折 + SP 广告加投 + 关联流量词竞价上调 30%");

        Map<String, Object> data = new HashMap<>();
        data.put("shopId", shopId);
        data.put("asin", asin);
        data.put("goal", goal);
        data.put("plan", plan);
        return Result.success(data);
    }
}
