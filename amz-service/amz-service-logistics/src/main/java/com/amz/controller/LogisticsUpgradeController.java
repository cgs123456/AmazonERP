package com.amz.controller;

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
 * 覆盖：物流商比价/库存调拨/头程费用分摊/签收差异
 */
@RestController
@RequestMapping("/logistics/v2")
public class LogisticsUpgradeController {

    @Autowired
    private LogisticsUpgradeService logisticsUpgradeService;

    // ==================== 物流商比价 ====================

    /** 保存物流商报价 */
    @ShopScoped
    @PostMapping("/quote")
    public Result<CarrierQuote> saveQuote(@RequestBody CarrierQuote quote) {
        return Result.success(logisticsUpgradeService.saveQuote(quote));
    }

    /** 查询报价列表 */
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

    // ==================== 库存调拨 ====================

    /** 创建调拨单 */
    @ShopScoped
    @PostMapping("/transfer")
    public Result<InventoryTransfer> createTransfer(@RequestBody InventoryTransfer transfer) {
        return Result.success(logisticsUpgradeService.createTransfer(transfer));
    }

    /** 审批调拨单 */
    @ShopScoped
    @PostMapping("/transfer/{id}/approve")
    public Result<InventoryTransfer> approveTransfer(@PathVariable Long id, @RequestParam boolean approved) {
        return Result.success(logisticsUpgradeService.approveTransfer(id, approved));
    }

    /** 确认调拨发出 */
    @ShopScoped
    @PostMapping("/transfer/{id}/ship")
    public Result<InventoryTransfer> shipTransfer(@PathVariable Long id,
                                                   @RequestParam String carrier,
                                                   @RequestParam String trackingNo) {
        return Result.success(logisticsUpgradeService.shipTransfer(id, carrier, trackingNo));
    }

    /** 确认调拨到货 */
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

    /** 处理签收差异 */
    @ShopScoped
    @PostMapping("/discrepancy/{id}/resolve")
    public Result<FbaReceiptDiscrepancy> resolveDiscrepancy(@PathVariable Long id, @RequestParam String resolution) {
        return Result.success(logisticsUpgradeService.resolveDiscrepancy(id, resolution));
    }
}
