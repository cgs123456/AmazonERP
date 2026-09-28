package com.amz.controller;

import com.amz.annotation.RequireRole;
import com.amz.annotation.ShopScoped;
import com.amz.model.Warehouse;
import com.amz.model.WarehouseInventory;
import com.amz.result.PageRequest;
import com.amz.result.Result;
import com.amz.service.WarehouseService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 海外仓管理 REST 端点。
 */
@RestController
@RequestMapping("/logistics/warehouse")
public class WarehouseController {

    @Autowired
    private WarehouseService warehouseService;

    /**
     * 创建仓库。
     * POST /logistics/warehouse
     */
    @RequireRole({"OPERATOR", "ADMIN"})
    @PostMapping
    public Result<Warehouse> create(@RequestBody Warehouse warehouse) {
        return Result.success(warehouseService.createWarehouse(warehouse));
    }

    /**
     * 更新仓库信息。
     * PUT /logistics/warehouse
     */
    @RequireRole({"OPERATOR", "ADMIN"})
    @PutMapping
    public Result<Warehouse> update(@RequestBody Warehouse warehouse) {
        return Result.success(warehouseService.updateWarehouse(warehouse));
    }

    /**
     * 查询店铺仓库列表。
     * GET /logistics/warehouse/list/{shopId}?warehouseType=
     */
    @ShopScoped
    @GetMapping("/list/{shopId}")
    public Result<List<Warehouse>> list(@PathVariable Long shopId,
                                        @RequestParam(required = false) String warehouseType) {
        return Result.success(warehouseService.listWarehouses(shopId, warehouseType));
    }

    /**
     * 查询仓库库存（按仓库 / SKU / 店铺筛选）。
     * GET /logistics/warehouse/inventory?warehouseId=&sku=&shopId=
     */
    @ShopScoped
    @GetMapping("/inventory")
    public Result<List<WarehouseInventory>> inventory(@RequestParam(required = false) Long warehouseId,
                                                       @RequestParam(required = false) String sku,
                                                       @RequestParam(required = false) Long shopId,
                                                       @RequestParam(required = false) Integer size,
                                                       @RequestParam(required = false) String cursor) {
        return Result.paged(warehouseService.listInventory(
                warehouseId, sku, shopId, PageRequest.of(size, cursor)));
    }

    /**
     * 更新库位码。
     * PUT /logistics/warehouse/inventory/{inventoryId}/location?locationCode=A-01-02
     */
    @RequireRole({"OPERATOR", "ADMIN"})
    @PutMapping("/inventory/{inventoryId}/location")
    public Result<WarehouseInventory> updateLocation(@PathVariable Long inventoryId,
                                                     @RequestParam String locationCode) {
        return Result.success(warehouseService.updateLocationCode(inventoryId, locationCode));
    }
}
