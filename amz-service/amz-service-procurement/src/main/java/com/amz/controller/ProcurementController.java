package com.amz.controller;

import com.amz.annotation.ShopScoped;
import com.amz.model.*;
import com.amz.result.Result;
import com.amz.service.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 采购供应链 REST 端点（升级版）。
 * <p>
 * 覆盖：供应商管理 / 采购计划 / 采购订单 / 质检 / FBA货件 / 批次管理 / 促销方案。
 */
@RestController
@RequestMapping("/procurement")
public class ProcurementController {

    @Autowired
    private ProcurementService procurementService;

    @Autowired
    private SupplierService supplierService;

    @Autowired
    private PurchasePlanService purchasePlanService;

    @Autowired
    private FbaShipmentService fbaShipmentService;

    // ==================== 供应商管理 ====================

    /** 创建供应商 */
    @ShopScoped
    @PostMapping("/supplier")
    public Result<Supplier> createSupplier(@RequestBody Supplier supplier) {
        return Result.success(supplierService.createSupplier(supplier));
    }

    /** 更新供应商 */
    @ShopScoped
    @PutMapping("/supplier/{id}")
    public Result<Supplier> updateSupplier(@PathVariable Long id, @RequestBody Supplier supplier) {
        supplier.setId(id);
        return Result.success(supplierService.updateSupplier(supplier));
    }

    /** 查询供应商列表 */
    @ShopScoped
    @GetMapping("/supplier/list/{shopId}")
    public Result<List<Supplier>> listSuppliers(@PathVariable Long shopId,
                                                 @RequestParam(required = false) String status) {
        return Result.success(supplierService.listSuppliers(shopId, status));
    }

    /** 获取供应商详情 */
    @ShopScoped
    @GetMapping("/supplier/{id}")
    public Result<Supplier> getSupplier(@PathVariable Long id) {
        return Result.success(supplierService.getSupplier(id));
    }

    /** 更新供应商状态（启用/禁用/拉黑） */
    @ShopScoped
    @PostMapping("/supplier/{id}/status")
    public Result<Boolean> updateSupplierStatus(@PathVariable Long id, @RequestParam String status) {
        return Result.success(supplierService.updateSupplierStatus(id, status));
    }

    /** 添加供应商-SKU 关联 */
    @ShopScoped
    @PostMapping("/supplier/product")
    public Result<SupplierProduct> addSupplierProduct(@RequestBody SupplierProduct sp) {
        return Result.success(supplierService.addSupplierProduct(sp));
    }

    /** 查询 SKU 的供应商列表 */
    @ShopScoped
    @GetMapping("/supplier/by-sku/{shopId}")
    public Result<List<SupplierProduct>> findSuppliersBySku(@PathVariable Long shopId,
                                                             @RequestParam String sku) {
        return Result.success(supplierService.findSuppliersBySku(shopId, sku));
    }

    /** 多供应商比价 */
    @ShopScoped
    @GetMapping("/supplier/compare/{shopId}")
    public Result<List<Map<String, Object>>> compareSupplierPrices(@PathVariable Long shopId,
                                                                    @RequestParam String sku) {
        return Result.success(supplierService.compareSupplierPrices(shopId, sku));
    }

    /** 供应商 KPI 报表 */
    @ShopScoped
    @GetMapping("/supplier/{id}/kpi")
    public Result<Map<String, Object>> supplierKpi(@PathVariable Long id) {
        return Result.success(supplierService.calculateSupplierKpi(id));
    }

    // ==================== 采购计划 ====================

    /** 创建采购计划 */
    @ShopScoped
    @PostMapping("/plan")
    public Result<PurchasePlan> createPlan(@RequestBody PurchasePlan plan) {
        return Result.success(purchasePlanService.createPlan(plan));
    }

    /** 提交采购计划审批 */
    @ShopScoped
    @PostMapping("/plan/{planId}/submit")
    public Result<PurchasePlan> submitPlan(@PathVariable Long planId) {
        return Result.success(purchasePlanService.submitForApproval(planId));
    }

    /** 审批采购计划 */
    @ShopScoped
    @PostMapping("/plan/{planId}/approve")
    public Result<PurchasePlan> approvePlan(@PathVariable Long planId,
                                            @RequestParam String operator,
                                            @RequestParam boolean approved,
                                            @RequestParam(required = false) String comment) {
        return Result.success(purchasePlanService.approve(planId, operator, approved, comment));
    }

    /** 采购计划转为采购订单 */
    @ShopScoped
    @PostMapping("/plan/{planId}/convert")
    public Result<Map<String, Object>> convertPlan(@PathVariable Long planId) {
        return Result.success(purchasePlanService.convertToOrder(planId));
    }

    /** 查询采购计划列表 */
    @ShopScoped
    @GetMapping("/plan/list/{shopId}")
    public Result<List<PurchasePlan>> listPlans(@PathVariable Long shopId,
                                                 @RequestParam(required = false) String status) {
        return Result.success(purchasePlanService.listPlans(shopId, status));
    }

    /** 取消采购计划 */
    @ShopScoped
    @PostMapping("/plan/{planId}/cancel")
    public Result<Boolean> cancelPlan(@PathVariable Long planId) {
        return Result.success(purchasePlanService.cancelPlan(planId));
    }

    // ==================== 采购订单 ====================

    /** 创建采购单 */
    @PostMapping("/order")
    public Result<PurchaseOrder> createOrder(@RequestBody PurchaseOrder order) {
        return Result.success(procurementService.createPurchaseOrder(order));
    }

    /** 提交采购单到 1688 */
    @PostMapping("/order/{orderId}/submit")
    public Result<PurchaseOrder> submitTo1688(@PathVariable Long orderId) {
        return Result.success(procurementService.submitTo1688(orderId));
    }

    /** 同步 1688 订单状态 */
    @PostMapping("/order/{orderId}/sync")
    public Result<PurchaseOrder> syncStatus(@PathVariable Long orderId) {
        return Result.success(procurementService.syncOrderStatus(orderId));
    }

    /** 取消采购单 */
    @PostMapping("/order/{orderId}/cancel")
    public Result<Boolean> cancelOrder(@PathVariable Long orderId) {
        return Result.success(procurementService.cancelPurchaseOrder(orderId));
    }

    /** 查询采购单列表 */
    @ShopScoped
    @GetMapping("/order/list/{shopId}")
    public Result<List<PurchaseOrder>> listOrders(@PathVariable Long shopId) {
        return Result.success(procurementService.listPurchaseOrders(shopId));
    }

    // ==================== 质检 ====================

    /** 提交质检结果 */
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

    // ==================== FBA 货件管理 ====================

    /** 创建 FBA 货件 */
    @ShopScoped
    @PostMapping("/fba/shipment")
    public Result<FbaShipment> createShipment(@RequestBody FbaShipment shipment) {
        return Result.success(fbaShipmentService.createShipment(shipment));
    }

    /** 更新 FBA 货件 */
    @ShopScoped
    @PutMapping("/fba/shipment/{id}")
    public Result<FbaShipment> updateShipment(@PathVariable Long id, @RequestBody FbaShipment shipment) {
        shipment.setId(id);
        return Result.success(fbaShipmentService.updateShipment(shipment));
    }

    /** 添加货件明细 */
    @ShopScoped
    @PostMapping("/fba/shipment/{shipmentId}/item")
    public Result<FbaShipmentItem> addShipmentItem(@PathVariable Long shipmentId,
                                                    @RequestBody FbaShipmentItem item) {
        item.setFbaShipmentId(shipmentId);
        return Result.success(fbaShipmentService.addShipmentItem(item));
    }

    /** 查询货件明细 */
    @ShopScoped
    @GetMapping("/fba/shipment/{shipmentId}/items")
    public Result<List<FbaShipmentItem>> listShipmentItems(@PathVariable Long shipmentId) {
        return Result.success(fbaShipmentService.listShipmentItems(shipmentId));
    }

    /** 查询货件列表 */
    @ShopScoped
    @GetMapping("/fba/shipment/list/{shopId}")
    public Result<List<FbaShipment>> listShipments(@PathVariable Long shopId,
                                                    @RequestParam(required = false) String status) {
        return Result.success(fbaShipmentService.listShipments(shopId, status));
    }

    /** 获取货件详情 */
    @ShopScoped
    @GetMapping("/fba/shipment/{id}")
    public Result<FbaShipment> getShipment(@PathVariable Long id) {
        return Result.success(fbaShipmentService.getShipment(id));
    }

    /** 更新货件状态 */
    @ShopScoped
    @PostMapping("/fba/shipment/{id}/status")
    public Result<FbaShipment> updateShipmentStatus(@PathVariable Long id, @RequestParam String status) {
        return Result.success(fbaShipmentService.updateShipmentStatus(id, status));
    }

    /** 确认发货 */
    @ShopScoped
    @PostMapping("/fba/shipment/{id}/ship")
    public Result<FbaShipment> confirmShipment(@PathVariable Long id,
                                                @RequestParam String carrier,
                                                @RequestParam String trackingNo) {
        return Result.success(fbaShipmentService.confirmShipment(id, carrier, trackingNo));
    }

    /** 头程费用分摊 */
    @ShopScoped
    @PostMapping("/fba/shipment/{shipmentId}/allocate")
    public Result<Map<String, Object>> allocateCosts(@PathVariable Long shipmentId) {
        return Result.success(fbaShipmentService.allocateCosts(shipmentId));
    }

    /** FBA 签收处理 */
    @ShopScoped
    @PostMapping("/fba/shipment/{shipmentId}/receive")
    public Result<Map<String, Object>> processReceipt(@PathVariable Long shipmentId,
                                                       @RequestBody List<Map<String, Object>> receivedItems) {
        return Result.success(fbaShipmentService.processReceipt(shipmentId, receivedItems));
    }

    // ==================== 批次管理 ====================

    /** 查询 SKU 库存批次（FIFO 顺序） */
    @ShopScoped
    @GetMapping("/batch/list/{shopId}")
    public Result<List<InventoryBatch>> listBatches(@PathVariable Long shopId,
                                                     @RequestParam String sku) {
        return Result.success(fbaShipmentService.listBatchesBySku(shopId, sku));
    }

    /** FIFO 出库 */
    @ShopScoped
    @PostMapping("/batch/fifo-outbound")
    public Result<List<Map<String, Object>>> fifoOutbound(@RequestParam Long shopId,
                                                          @RequestParam String sku,
                                                          @RequestParam Integer quantity) {
        return Result.success(fbaShipmentService.fifoOutbound(shopId, sku, quantity));
    }

    // ==================== 促销方案（供 Agent 工具调用） ====================

    /** 查询促销计划 */
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
