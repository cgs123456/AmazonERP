package com.amz.controller;

import com.amz.annotation.RequireRole;
import com.amz.annotation.ShopScoped;
import com.amz.model.*;
import com.amz.result.Result;
import com.amz.service.LogisticsUpgradeService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 物流升级 REST 端点。
 * <p>
 * 覆盖：物流商比价 / 库存调拨 / 头程费用分摊 / 签收差异
 * <p>
 * <b>鉴权分工：</b>读接口只加 {@link ShopScoped}（VIEWER 也可查）；
 * 写接口再加 {@link RequireRole}（OPERATOR / ADMIN）——审批、发货、结案这类动作
 * 会直接改变单据状态与成本口径，不应由只读角色触发。
 * <p>
 * <b>注意：</b>{@code @ShopScoped} 只校验请求参数里的 shopId。
 * 形如 {@code /transfer/{id}/approve} 的路径没有 shopId 可供切面检查，
 * 其归属校验落在 Service 实现内部（见 {@code LogisticsUpgradeServiceImpl}），
 * 因此这些端点上的注解只代表「角色门槛」，不代表已完成店铺校验。
 */
@RestController
@RequestMapping("/logistics/v2")
public class LogisticsUpgradeController {

    @Autowired
    private LogisticsUpgradeService logisticsUpgradeService;

    // ==================== 物流商比价 ====================

    /** 保存物流商报价 */
    @RequireRole({"OPERATOR", "ADMIN"})
    @ShopScoped
    @PostMapping("/quote")
    public Result<CarrierQuote> saveQuote(@RequestBody CarrierQuote quote) {
        return Result.success(logisticsUpgradeService.saveQuote(quote));
    }

    /** 查询报价列表（仅返回有效期内报价） */
    @ShopScoped
    @GetMapping("/quote/list/{shopId}")
    public Result<List<CarrierQuote>> listQuotes(@PathVariable Long shopId,
                                                  @RequestParam(required = false) String serviceType) {
        return Result.success(logisticsUpgradeService.listQuotes(shopId, serviceType));
    }

    /** 运费比价 */
    @ShopScoped
    @GetMapping("/quote/compare/{shopId}")
    public Result<Map<String, Object>> compareQuotes(@PathVariable Long shopId,
                                                      @RequestParam String originPort,
                                                      @RequestParam String destinationPort,
                                                      @RequestParam(required = false) BigDecimal weightKg,
                                                      @RequestParam(required = false) BigDecimal volumeCbm) {
        return Result.success(logisticsUpgradeService.compareQuotes(shopId, originPort, destinationPort, weightKg, volumeCbm));
    }

    /**
     * 把已过失效日期但状态仍为 ACTIVE 的报价批量置为 EXPIRED。
     * <p>
     * 报价过期本应由定时任务收口，在任务尚未覆盖到之前提供一次手动入口，
     * 避免过期运价继续参与选商。
     */
    @RequireRole({"OPERATOR", "ADMIN"})
    @ShopScoped
    @PostMapping("/quote/expire-outdated/{shopId}")
    public Result<Integer> expireOutdatedQuotes(@PathVariable Long shopId) {
        return Result.success(logisticsUpgradeService.expireOutdatedQuotes(shopId));
    }

    // ==================== 库存调拨 ====================

    /** 创建调拨单 */
    @RequireRole({"OPERATOR", "ADMIN"})
    @ShopScoped
    @PostMapping("/transfer")
    public Result<InventoryTransfer> createTransfer(@RequestBody InventoryTransfer transfer) {
        return Result.success(logisticsUpgradeService.createTransfer(transfer));
    }

    /** 审批调拨单 */
    @RequireRole({"OPERATOR", "ADMIN"})
    @ShopScoped
    @PostMapping("/transfer/{id}/approve")
    public Result<InventoryTransfer> approveTransfer(@PathVariable Long id, @RequestParam boolean approved) {
        return Result.success(logisticsUpgradeService.approveTransfer(id, approved));
    }

    /** 确认调拨发出 */
    @RequireRole({"OPERATOR", "ADMIN"})
    @ShopScoped
    @PostMapping("/transfer/{id}/ship")
    public Result<InventoryTransfer> shipTransfer(@PathVariable Long id,
                                                   @RequestParam String carrier,
                                                   @RequestParam String trackingNo) {
        return Result.success(logisticsUpgradeService.shipTransfer(id, carrier, trackingNo));
    }

    /** 确认调拨到货 */
    @RequireRole({"OPERATOR", "ADMIN"})
    @ShopScoped
    @PostMapping("/transfer/{id}/receive")
    public Result<InventoryTransfer> receiveTransfer(@PathVariable Long id) {
        return Result.success(logisticsUpgradeService.receiveTransfer(id));
    }

    /** 查询调拨单列表 */
    @ShopScoped
    @GetMapping("/transfer/list/{shopId}")
    public Result<List<InventoryTransfer>> listTransfers(@PathVariable Long shopId,
                                                          @RequestParam(required = false) String status) {
        return Result.success(logisticsUpgradeService.listTransfers(shopId, status));
    }

    // ==================== 头程费用分摊 ====================

    /** 查询货件头程费用分摊明细 */
    @ShopScoped
    @GetMapping("/freight/{shipmentId}")
    public Result<List<FreightAllocation>> listAllocations(@PathVariable Long shipmentId) {
        return Result.success(logisticsUpgradeService.listAllocations(shipmentId));
    }

    /** 按分摊方法计算头程费用 */
    @RequireRole({"OPERATOR", "ADMIN"})
    @ShopScoped
    @PostMapping("/freight/{shipmentId}/calculate")
    public Result<Map<String, Object>> calculateFreight(@PathVariable Long shipmentId,
                                                         @RequestParam(defaultValue = "WEIGHT") String method,
                                                         @RequestParam BigDecimal totalFreight,
                                                         @RequestParam(required = false) BigDecimal totalDuty,
                                                         @RequestParam(required = false) BigDecimal totalInsurance) {
        return Result.success(logisticsUpgradeService.calculateFreightAllocation(shipmentId, method, totalFreight, totalDuty, totalInsurance));
    }

    // ==================== FBA 签收差异 ====================

    /** 保存签收差异 */
    @RequireRole({"OPERATOR", "ADMIN"})
    @ShopScoped
    @PostMapping("/discrepancy")
    public Result<FbaReceiptDiscrepancy> saveDiscrepancy(@RequestBody FbaReceiptDiscrepancy discrepancy) {
        return Result.success(logisticsUpgradeService.saveDiscrepancy(discrepancy));
    }

    /** 查询签收差异列表 */
    @ShopScoped
    @GetMapping("/discrepancy/list/{shopId}")
    public Result<List<FbaReceiptDiscrepancy>> listDiscrepancies(@PathVariable Long shopId,
                                                                  @RequestParam(required = false) String status) {
        return Result.success(logisticsUpgradeService.listDiscrepancies(shopId, status));
    }

    /** 将签收差异转入核查中 */
    @RequireRole({"OPERATOR", "ADMIN"})
    @ShopScoped
    @PostMapping("/discrepancy/{id}/investigate")
    public Result<FbaReceiptDiscrepancy> startInvestigating(@PathVariable Long id) {
        return Result.success(logisticsUpgradeService.startInvestigating(id));
    }

    /** 处理签收差异 */
    @RequireRole({"OPERATOR", "ADMIN"})
    @ShopScoped
    @PostMapping("/discrepancy/{id}/resolve")
    public Result<FbaReceiptDiscrepancy> resolveDiscrepancy(@PathVariable Long id, @RequestParam String resolution) {
        return Result.success(logisticsUpgradeService.resolveDiscrepancy(id, resolution));
    }
}
