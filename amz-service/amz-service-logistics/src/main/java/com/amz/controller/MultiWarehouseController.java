package com.amz.controller;

import com.amz.annotation.ShopScoped;
import com.amz.model.*;
import com.amz.result.Result;
import com.amz.service.MultiWarehouseService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 多仓库存管理 REST 端点。
 */
@RestController
@RequestMapping("/logistics/warehouse")
public class MultiWarehouseController {

    @Autowired
    private MultiWarehouseService multiWarehouseService;

    // ==================== 库存快照 ====================

    /** 保存/更新库存快照 */
    @ShopScoped
    @PostMapping("/stock")
    public Result<WarehouseStock> saveStock(@RequestBody WarehouseStock stock) {
        return Result.success(multiWarehouseService.saveStock(stock));
    }

    /** 查询库存列表 */
    @ShopScoped
    @GetMapping("/stock/list/{shopId}")
    public Result<List<WarehouseStock>> listStock(@PathVariable Long shopId,
                                                   @RequestParam(required = false) String sku,
                                                   @RequestParam(required = false) Long warehouseId) {
        return Result.success(multiWarehouseService.listStock(shopId, sku, warehouseId));
    }

    /** 全局库存视图（按仓库类型聚合） */
    @ShopScoped
    @GetMapping("/stock/view/{shopId}")
    public Result<Map<String, Object>> globalView(@PathVariable Long shopId,
                                                   @RequestParam(required = false) String sku) {
        return Result.success(multiWarehouseService.globalInventoryView(shopId, sku));
    }

    /** 库龄分析 */
    @ShopScoped
    @GetMapping("/stock/aging/{shopId}")
    public Result<Map<String, Object>> agingAnalysis(@PathVariable Long shopId) {
        return Result.success(multiWarehouseService.agingAnalysis(shopId));
    }

    // ==================== 库存预警 ====================

    /** 创建预警规则 */
    @ShopScoped
    @PostMapping("/alert")
    public Result<InventoryAlert> createAlert(@RequestBody InventoryAlert alert) {
        return Result.success(multiWarehouseService.createAlert(alert));
    }

    /** 查询预警规则 */
    @ShopScoped
    @GetMapping("/alert/list/{shopId}")
    public Result<List<InventoryAlert>> listAlerts(@PathVariable Long shopId,
                                                    @RequestParam(required = false) Boolean enabled) {
        return Result.success(multiWarehouseService.listAlerts(shopId, enabled));
    }

    /** 启用/禁用预警规则 */
    @ShopScoped
    @PostMapping("/alert/{id}/toggle")
    public Result<Boolean> toggleAlert(@PathVariable Long id, @RequestParam boolean enabled) {
        multiWarehouseService.toggleAlert(id, enabled);
        return Result.success(true);
    }

    /** 执行预警检查 */
    @ShopScoped
    @GetMapping("/alert/check/{shopId}")
    public Result<Map<String, Object>> checkAlerts(@PathVariable Long shopId) {
        return Result.success(multiWarehouseService.checkAlerts(shopId));
    }
}
