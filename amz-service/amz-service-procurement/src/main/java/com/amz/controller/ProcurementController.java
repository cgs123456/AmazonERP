package com.amz.controller;

import com.amz.dto.BatchCostSummary;
import com.amz.dto.ReceiptShortage;
import com.amz.dto.SupplierPriceCsvImportReport;
import com.amz.util.CsvImportUtil;
import com.amz.annotation.OperLog;
import com.amz.annotation.RequireRole;
import com.amz.annotation.ShopScoped;
import com.amz.model.FbaShipment;
import com.amz.model.FbaShipmentItem;
import com.amz.model.InventoryBatch;
import com.amz.model.PurchaseApproval;
import com.amz.model.PurchaseOrder;
import com.amz.model.PurchasePlan;
import com.amz.model.QualityCheck;
import com.amz.model.Supplier;
import com.amz.model.SupplierProduct;
import com.amz.result.PageRequest;
import com.amz.result.Result;
import com.amz.service.FbaShipmentService;
import com.amz.service.ProcurementService;
import com.amz.service.PurchasePlanService;
import com.amz.service.SupplierService;
import com.amz.service.impl.SupplierPriceCsvImportService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

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
@Slf4j
public class ProcurementController {

    @Autowired
    private ProcurementService procurementService;

    @Autowired
    private SupplierService supplierService;

    @Autowired
    private SupplierPriceCsvImportService supplierPriceCsvImportService;

    @Autowired
    private PurchasePlanService purchasePlanService;

    @Autowired
    private FbaShipmentService fbaShipmentService;

    // ==================== 供应商管理 ====================

    /** 创建供应商 */
    @ShopScoped
    @RequireRole({"OPERATOR", "ADMIN"})
    @OperLog(module = "采购供应链", action = "新增", description = "创建供应商")
    @PostMapping("/supplier")
    public Result<Supplier> createSupplier(@RequestBody Supplier supplier) {
        return Result.success(supplierService.createSupplier(supplier));
    }

    /** 更新供应商 */
    @ShopScoped
    @RequireRole({"OPERATOR", "ADMIN"})
    @OperLog(module = "采购供应链", action = "修改", description = "更新供应商")
    @PutMapping("/supplier/{id}")
    public Result<Supplier> updateSupplier(@PathVariable Long id, @RequestBody Supplier supplier) {
        supplier.setId(id);
        return Result.success(supplierService.updateSupplier(supplier));
    }

    /** 查询供应商列表（游标分页；keyword 模糊匹配名称/编码/联系人） */
    @ShopScoped
    @GetMapping("/supplier/list/{shopId}")
    public Result<List<Supplier>> listSuppliers(@PathVariable Long shopId,
                                                 @RequestParam(value = "status", required = false) String status,
                                                 @RequestParam(value = "keyword", required = false) String keyword,
                                                 @RequestParam(value = "size", required = false) Integer size,
                                                 @RequestParam(value = "cursor", required = false) String cursor) {
        return Result.paged(supplierService.listSuppliers(
                shopId, status, keyword, PageRequest.of(size, cursor)));
    }

    /** 获取供应商详情 */
    @ShopScoped
    @GetMapping("/supplier/{id}")
    public Result<Supplier> getSupplier(@PathVariable Long id) {
        return Result.success(supplierService.getSupplier(id));
    }

    /** 更新供应商状态（启用/禁用/拉黑） */
    @ShopScoped
    @RequireRole({"OPERATOR", "ADMIN"})
    @OperLog(module = "采购供应链", action = "启停", description = "启用或停用供应商")
    @PostMapping("/supplier/{id}/status")
    public Result<Boolean> updateSupplierStatus(@PathVariable Long id, @RequestParam String status) {
        return Result.success(supplierService.updateSupplierStatus(id, status));
    }

    /** 添加供应商-SKU 关联 */
    @ShopScoped
    @RequireRole({"OPERATOR", "ADMIN"})
    @OperLog(module = "采购供应链", action = "新增", description = "维护供货价")
    @PostMapping("/supplier/product")
    public Result<SupplierProduct> addSupplierProduct(@RequestBody SupplierProduct sp) {
        return Result.success(supplierService.addSupplierProduct(sp));
    }

    /**
     * CSV 批量维护供货价（A6 第三个域）。
     * <p>
     * POST /procurement/supplier/product/import/csv?shopId=1&amp;supplierId=2（multipart，字段名 file）
     * <p>
     * 供应商由请求参数选定（选哪个供应商传它的价目表），每行只用 sku 定位——不把内部
     * supplier_id 塞进 Excel 让使用者手抄。必需列 sku；可维护列 supplier_sku_code /
     * supplier_offer_id / supply_price / moq / lead_time_days / packaging_spec /
     * unit_weight / unit_volume，留空=保持原值。
     * <p>
     * 两条刻意限制（见 SupplierPriceCsvImportService 类注释）：
     * ① 只更新已存在的供货关系，不新建（表无唯一键，且新建走的是纯 insert 的
     *    addSupplierProduct，语义是「新增供货关系」不是「改价」）；
     * ② is_preferred / 身份 / 状态列出现在文件里即按行拒绝——设首选有跨行副作用，
     *    要放单条表单里做，不进逐行循环。
     */
    @RequireRole({"OPERATOR", "ADMIN"})
    @ShopScoped
    @OperLog(module = "采购供应链", action = "CSV导入", description = "批量维护供货价")
    @PostMapping(value = "/supplier/product/import/csv", consumes = "multipart/form-data")
    public Result<SupplierPriceCsvImportReport> importSupplierPriceCsv(
            @RequestParam Long shopId,
            @RequestParam Long supplierId,
            @RequestParam("file") MultipartFile file) {
        if (file == null || file.isEmpty()) {
            return Result.failure("上传文件为空");
        }
        if (file.getSize() > CsvImportUtil.DEFAULT_MAX_BYTES) {
            return Result.failure("文件超过大小上限 "
                    + (CsvImportUtil.DEFAULT_MAX_BYTES / 1024 / 1024) + "MB");
        }
        String name = file.getOriginalFilename();
        if (name == null || !name.toLowerCase().endsWith(".csv")) {
            return Result.failure("只支持 .csv 文件（Excel 请另存为 CSV UTF-8）");
        }
        try {
            return Result.success(supplierPriceCsvImportService.importCsv(shopId, supplierId,
                    new java.io.BufferedReader(new java.io.InputStreamReader(
                            file.getInputStream(), java.nio.charset.StandardCharsets.UTF_8))));
        } catch (CsvImportUtil.StructuralException e) {
            return Result.failure(e.getMessage());
        } catch (Exception e) {
            log.warn("供货价 CSV 导入失败 shopId={} supplierId={}: {}", shopId, supplierId, e.getMessage());
            return Result.failure("导入失败：" + e.getMessage());
        }
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
    @RequireRole({"OPERATOR", "ADMIN"})
    @OperLog(module = "采购计划", action = "新增", description = "创建补货采购计划")
    @PostMapping("/plan")
    public Result<PurchasePlan> createPlan(@RequestBody PurchasePlan plan) {
        return Result.success(purchasePlanService.createPlan(plan));
    }

    /** 提交采购计划审批 */
    @ShopScoped
    @RequireRole({"OPERATOR", "ADMIN"})
    @OperLog(module = "采购计划", action = "提交", description = "提交计划进入审批")
    @PostMapping("/plan/{planId}/submit")
    public Result<PurchasePlan> submitPlan(@PathVariable Long planId) {
        return Result.success(purchasePlanService.submitForApproval(planId));
    }

    /** 审批采购计划 */
    @ShopScoped
    @RequireRole({"OPERATOR", "ADMIN"})
    @OperLog(module = "采购计划", action = "审批", description = "审批通过或驳回（影响后续采购金额）")
    @PostMapping("/plan/{planId}/approve")
    public Result<PurchasePlan> approvePlan(@PathVariable Long planId,
                                            @RequestParam String operator,
                                            @RequestParam boolean approved,
                                            @RequestParam(required = false) String comment) {
        return Result.success(purchasePlanService.approve(planId, operator, approved, comment));
    }

    /** 采购计划审批留痕：每一次通过/驳回一条，含操作人与意见 */
    @ShopScoped
    @GetMapping("/plan/{planId}/approvals")
    public Result<List<PurchaseApproval>> listPlanApprovals(@PathVariable Long planId) {
        return Result.success(purchasePlanService.listPlanApprovals(planId));
    }

    /** 采购计划转为采购订单 */
    @ShopScoped
    @RequireRole({"OPERATOR", "ADMIN"})
    @OperLog(module = "采购计划", action = "转单", description = "审批通过后转为采购单")
    @PostMapping("/plan/{planId}/convert")
    public Result<Map<String, Object>> convertPlan(@PathVariable Long planId) {
        return Result.success(purchasePlanService.convertToOrder(planId));
    }

    /**
     * 查询采购计划列表（游标分页）。
     * GET /procurement/plan/list/{shopId}?status=&size=&cursor=
     */
    @ShopScoped
    @GetMapping("/plan/list/{shopId}")
    public Result<List<PurchasePlan>> listPlans(@PathVariable Long shopId,
                                                 @RequestParam(value = "status", required = false) String status,
                                                 @RequestParam(value = "size", required = false) Integer size,
                                                 @RequestParam(value = "cursor", required = false) String cursor) {
        return Result.paged(purchasePlanService.listPlans(
                shopId, status, PageRequest.of(size, cursor)));
    }

    /** 取消采购计划 */
    @ShopScoped
    @RequireRole({"OPERATOR", "ADMIN"})
    @OperLog(module = "采购计划", action = "取消", description = "取消采购计划")
    @PostMapping("/plan/{planId}/cancel")
    public Result<Boolean> cancelPlan(@PathVariable Long planId) {
        return Result.success(purchasePlanService.cancelPlan(planId));
    }

    // ==================== 采购订单 ====================

    /** 创建采购单 */
    @RequireRole({"OPERATOR", "ADMIN"})
    @OperLog(module = "采购单", action = "新增", description = "创建采购单")
    @PostMapping("/order")
    public Result<PurchaseOrder> createOrder(@RequestBody PurchaseOrder order) {
        return Result.success(procurementService.createPurchaseOrder(order));
    }

    /** 提交采购单到 1688 */
    @RequireRole({"OPERATOR", "ADMIN"})
    @OperLog(module = "采购单", action = "提交", description = "提交到 1688 下单")
    @PostMapping("/order/{orderId}/submit")
    public Result<PurchaseOrder> submitTo1688(@PathVariable Long orderId) {
        return Result.success(procurementService.submitTo1688(orderId));
    }

    /** 同步 1688 订单状态 */
    @RequireRole({"OPERATOR", "ADMIN"})
    @OperLog(module = "采购供应链", action = "同步", description = "同步 1688 采购单状态")
    @PostMapping("/order/{orderId}/sync")
    public Result<PurchaseOrder> syncStatus(@PathVariable Long orderId) {
        return Result.success(procurementService.syncOrderStatus(orderId));
    }

    /** 取消采购单 */
    @RequireRole({"OPERATOR", "ADMIN"})
    @OperLog(module = "采购单", action = "取消", description = "取消采购单")
    @PostMapping("/order/{orderId}/cancel")
    public Result<Boolean> cancelOrder(@PathVariable Long orderId) {
        return Result.success(procurementService.cancelPurchaseOrder(orderId));
    }

    /**
     * 查询采购单列表（游标分页）。
     * GET /procurement/order/list/{shopId}?size=&cursor=
     * <p>
     * 响应带 {@code _page}：{@code truncated=true} 表示本页不是全量，
     * 审批类操作必须翻完页，不能只看第一页。
     */
    @ShopScoped
    @GetMapping("/order/list/{shopId}")
    public Result<List<PurchaseOrder>> listOrders(@PathVariable Long shopId,
                                                  @RequestParam(required = false) Integer size,
                                                  @RequestParam(required = false) String cursor) {
        return Result.paged(procurementService.listPurchaseOrders(shopId, PageRequest.of(size, cursor)));
    }

    /**
     * 成本可确认的采购单（供财务生成 PROCUREMENT 凭证，游标分页）。
     * GET /procurement/order/voucher-source/{shopId}?size=&cursor=
     * <p>
     * 与 /order/list 的区别只在状态过滤：这里只出 QC_PASSED/RECEIVED/COMPLETED 三种，
     * 未成交的单不会变成凭证；调用方必须按 _page 翻完，否则会漏出凭证。
     */
    @ShopScoped
    @GetMapping("/order/voucher-source/{shopId}")
    public Result<List<PurchaseOrder>> listOrdersForVoucher(@PathVariable Long shopId,
                                                            @RequestParam(required = false) Integer size,
                                                            @RequestParam(required = false) String cursor) {
        return Result.paged(procurementService.listOrdersForVoucher(shopId, PageRequest.of(size, cursor)));
    }

    // ==================== 质检 ====================

    /** 提交质检结果 */
    @RequireRole({"OPERATOR", "ADMIN"})
    @OperLog(module = "采购供应链", action = "质检", description = "提交质检结果")
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
    @RequireRole({"OPERATOR", "ADMIN"})
    @OperLog(module = "FBA货件", action = "新增", description = "创建 FBA 发货计划")
    @PostMapping("/fba/shipment")
    public Result<FbaShipment> createShipment(@RequestBody FbaShipment shipment) {
        return Result.success(fbaShipmentService.createShipment(shipment));
    }

    /** 更新 FBA 货件 */
    @ShopScoped
    @RequireRole({"OPERATOR", "ADMIN"})
    @OperLog(module = "采购供应链", action = "修改", description = "更新 FBA 货件")
    @PutMapping("/fba/shipment/{id}")
    public Result<FbaShipment> updateShipment(@PathVariable Long id, @RequestBody FbaShipment shipment) {
        shipment.setId(id);
        return Result.success(fbaShipmentService.updateShipment(shipment));
    }

    /** 添加货件明细 */
    @ShopScoped
    @RequireRole({"OPERATOR", "ADMIN"})
    @OperLog(module = "采购供应链", action = "新增", description = "添加货件明细")
    @PostMapping("/fba/shipment/{shipmentId}/item")
    public Result<FbaShipmentItem> addShipmentItem(@PathVariable Long shipmentId,
                                                    @RequestBody FbaShipmentItem item) {
        item.setFbaShipmentId(shipmentId);
        return Result.success(fbaShipmentService.addShipmentItem(item));
    }

    /** 查询货件明细（id DESC keyset 游标分页） */
    @ShopScoped
    @GetMapping("/fba/shipment/{shipmentId}/items")
    public Result<List<FbaShipmentItem>> listShipmentItems(
            @PathVariable Long shipmentId,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String cursor) {
        return Result.paged(fbaShipmentService.listShipmentItems(
                shipmentId, PageRequest.of(size, cursor)));
    }

    /** 查询货件列表 */
    @ShopScoped
    @GetMapping("/fba/shipment/list/{shopId}")
    public Result<List<FbaShipment>> listShipments(@PathVariable Long shopId,
                                                    @RequestParam(value = "status", required = false) String status,
                                                    @RequestParam(required = false) Integer size,
                                                    @RequestParam(required = false) String cursor) {
        return Result.paged(fbaShipmentService.listShipments(
                shopId, status, PageRequest.of(size, cursor)));
    }

    /** 获取货件详情 */
    @ShopScoped
    @GetMapping("/fba/shipment/{id}")
    public Result<FbaShipment> getShipment(@PathVariable Long id) {
        return Result.success(fbaShipmentService.getShipment(id));
    }

    /** 更新货件状态 */
    @ShopScoped
    @RequireRole({"OPERATOR", "ADMIN"})
    @OperLog(module = "采购供应链", action = "状态变更", description = "推进货件状态")
    @PostMapping("/fba/shipment/{id}/status")
    public Result<FbaShipment> updateShipmentStatus(@PathVariable Long id, @RequestParam String status) {
        return Result.success(fbaShipmentService.updateShipmentStatus(id, status));
    }

    /** 确认发货 */
    @ShopScoped
    @RequireRole({"OPERATOR", "ADMIN"})
    @OperLog(module = "FBA货件", action = "发货", description = "标记货件已发运")
    @PostMapping("/fba/shipment/{id}/ship")
    public Result<FbaShipment> confirmShipment(@PathVariable Long id,
                                                @RequestParam String carrier,
                                                @RequestParam String trackingNo) {
        return Result.success(fbaShipmentService.confirmShipment(id, carrier, trackingNo));
    }

    /** 头程费用分摊 */
    @ShopScoped
    @RequireRole({"OPERATOR", "ADMIN"})
    @OperLog(module = "采购供应链", action = "分摊", description = "货件成本分摊")
    @PostMapping("/fba/shipment/{shipmentId}/allocate")
    public Result<Map<String, Object>> allocateCosts(@PathVariable Long shipmentId) {
        return Result.success(fbaShipmentService.allocateCosts(shipmentId));
    }

    /** FBA 签收处理 */
    @ShopScoped
    @RequireRole({"OPERATOR", "ADMIN"})
    @OperLog(module = "FBA货件", action = "签收", description = "货件签收并回补批次库存")
    @PostMapping("/fba/shipment/{shipmentId}/receive")
    public Result<Map<String, Object>> processReceipt(@PathVariable Long shipmentId,
                                                       @RequestBody List<Map<String, Object>> receivedItems) {
        return Result.success(fbaShipmentService.processReceipt(shipmentId, receivedItems));
    }

    /**
     * 入库短收待登记清单（GET /procurement/fba/shipment/receipt-shortages/{shopId}?size=&cursor=）。
     * <p>
     * 给财务侧「登记入库短收」表单当数据来源：只交事实（应发/实发/短收 + 我方成本口径），
     * 金额与币种由人在登记时确认，因为分摊金额没有记币种。
     */
    @ShopScoped
    @GetMapping("/fba/shipment/receipt-shortages/{shopId}")
    public Result<List<ReceiptShortage>> listReceiptShortages(@PathVariable Long shopId,
                                                              @RequestParam(required = false) Integer size,
                                                              @RequestParam(required = false) String cursor) {
        return Result.paged(fbaShipmentService.listReceiptShortages(shopId, PageRequest.of(size, cursor)));
    }

    // ==================== 批次管理 ====================

    /** 查询 SKU 库存批次（按 inbound_date、id 升序，FIFO 游标分页） */
    @ShopScoped
    @GetMapping("/batch/list/{shopId}")
    public Result<List<InventoryBatch>> listBatches(@PathVariable Long shopId,
                                                     @RequestParam String sku,
                                                     @RequestParam(value = "size", required = false) Integer size,
                                                     @RequestParam(value = "cursor", required = false) String cursor) {
        return Result.paged(fbaShipmentService.listBatchesBySku(
                shopId, sku, PageRequest.of(size, cursor)));
    }

    /** 查询 SKU 的 ACTIVE 批次成本聚合值（供财务跨服务消费） */
    @ShopScoped
    @GetMapping("/batch/cost-summary/{shopId}")
    public Result<BatchCostSummary> batchCostSummary(@PathVariable Long shopId,
                                                      @RequestParam String sku) {
        return Result.success(fbaShipmentService.getBatchCostSummary(shopId, sku));
    }

    /** FIFO 出库 */
    @ShopScoped
    @RequireRole({"OPERATOR", "ADMIN"})
    @OperLog(module = "库存批次", action = "出库", description = "FIFO 批次出库扣减")
    @PostMapping("/batch/fifo-outbound")
    public Result<List<Map<String, Object>>> fifoOutbound(@RequestParam Long shopId,
                                                          @RequestParam String sku,
                                                          @RequestParam Integer quantity) {
        return Result.success(fbaShipmentService.fifoOutbound(shopId, sku, quantity));
    }

    /** FIFO 回补（退货/冲回，与出库对称） */
    @ShopScoped
    @RequireRole({"OPERATOR", "ADMIN"})
    @OperLog(module = "库存批次", action = "回补", description = "FIFO 批次回补（退货冲回）")
    @PostMapping("/batch/fifo-return")
    public Result<List<Map<String, Object>>> fifoReturn(@RequestParam Long shopId,
                                                        @RequestParam String sku,
                                                        @RequestParam Integer quantity,
                                                        @RequestParam(required = false) String originalBatchNo) {
        return Result.success(fbaShipmentService.fifoReturn(shopId, sku, quantity, originalBatchNo));
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
