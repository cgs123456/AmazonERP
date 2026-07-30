package com.amz.service;

import com.amz.model.Warehouse;
import com.amz.model.WarehouseInventory;

import java.util.List;

/**
 * 海外仓服务接口。
 * 负责仓库 CRUD、库存查询与库位管理。
 */
public interface WarehouseService {

    /**
     * 创建仓库。
     */
    Warehouse createWarehouse(Warehouse warehouse);

    /**
     * 更新仓库信息。
     */
    Warehouse updateWarehouse(Warehouse warehouse);

    /**
     * 查询店铺仓库列表。
     */
    List<Warehouse> listWarehouses(Long shopId, String warehouseType);

    /**
     * 查询仓库库存列表（按仓库 / SKU 筛选）。
     */
    List<WarehouseInventory> listInventory(Long warehouseId, String sku, Long shopId);

    /**
     * 设置 / 更新库位码。
     */
    WarehouseInventory updateLocationCode(Long inventoryId, String locationCode);

    /**
     * 增加库存数量（入库到货时调用）。
     */
    WarehouseInventory increaseInventory(Long warehouseId, Long shopId, String sku,
                                        Integer quantity, String batchNo, String locationCode);

    /**
     * 扣减库存数量（出库发货时调用）。
     */
    WarehouseInventory decreaseInventory(Long warehouseId, String sku, Integer quantity);
}
