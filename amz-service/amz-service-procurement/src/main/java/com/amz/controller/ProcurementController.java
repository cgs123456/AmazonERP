package com.amz.controller;

import com.amz.dto.SupplierPriceCsvImportReport;
import com.amz.util.CsvImportUtil;
import com.amz.annotation.OperLog;
import com.amz.annotation.RequireRole;
import com.amz.annotation.ShopScoped;
import com.amz.model.PurchaseApproval;
import com.amz.model.PurchaseOrder;
import com.amz.model.PurchasePlan;
import com.amz.model.QualityCheck;
import com.amz.model.Supplier;
import com.amz.model.SupplierProduct;
import com.amz.result.PageRequest;
import com.amz.result.Result;
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

    // ==================== 促销方案（供 Agent 工具调用） ====================

    /**
     * 生成促销方案。
     *
     * <h2>§S33.22：此前这里返回写死的模板常量</h2>
     * 原实现无论传什么 shopId / asin / goal，都返回同一套数字：
     * {@code Lightning Deal}、{@code discountRate=0.20}、
     * {@code estimatedSalesUplift="150%"}、{@code budget="$500 广告预算"}。
     * <p>
     * 前端**故意没接**这个端点（见 {@code amz-frontend/src/api/procurement.ts}
     * 顶部注释：「接进 UI 等于把模板文案显示成经营建议」）。
     * 但 Agent 工具 {@code generate_promotion_plan} **正在调它**
     * （{@code ErpProcurementTools} → {@code ProcurementServiceClient}），
     * 于是 LLM 会把这段模板当成真实建议讲给运营听——前端守住了，Agent 这条路没守住。
     *
     * <h2>为什么改成显式失败而不是继续返回常量</h2>
     * 真实促销方案需要：历史销量与转化、竞品价格、库存与在途、平台活动日历、
     * 毛利下限。本服务一条都没有（procurement 域连促销表都没有），
     * 在缺这些数据的情况下给出「降价 20% + 加投 $500」不是建议，是**编造**。
     * <p>
     * 与索评（{@code CustomerServiceImpl.sendReviewSolicitations}）、
     * 差评订单匹配（{@code CustomerEmailServiceImpl.matchNegativeReviewToOrder}）
     * 同一处理口径：**能力没做就诚实失败，绝不返回看起来像结果的东西**。
     * 失败信息里写清楚缺哪些数据，让调用方（Agent / 前端）能原样转达给运营。
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
        log.info("促销方案请求被拒绝（数据源未接入）：shopId={} asin={} goal={}", shopId, asin, goal);
        // 文案里刻意**不复述**被移除的那套模板数字：这段文字会原样交给 LLM，
        // 带上「Lightning Deal / 20% / $500」反而可能被模型复述成方案。
        return Result.failure("促销方案尚未接入：生成方案需要历史销量与转化、竞品价格、"
                + "库存与在途、平台活动日历与毛利下限，本服务目前没有这些数据源。"
                + "此前该端点返回的是与店铺无关的模板常量，已移除——"
                + "把模板当经营建议的风险高于「暂时没有建议」。");
    }
}
