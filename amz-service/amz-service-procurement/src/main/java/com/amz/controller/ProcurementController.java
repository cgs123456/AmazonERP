package com.amz.controller;

import com.amz.model.PurchaseOrder;
import com.amz.model.QualityCheck;
import com.amz.result.Result;
import com.amz.service.ProcurementService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

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
}
