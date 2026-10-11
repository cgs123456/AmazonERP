package com.amz.service;

import com.amz.model.Warehouse;
import com.amz.model.WarehouseInventory;
import com.amz.result.PageRequest;
import com.amz.result.PageResult;

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
    PageResult<WarehouseInventory> listInventory(Long warehouseId, String sku, Long shopId, PageRequest page);

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
     * <p>
     * 按仓库 + SKU 定位行，店铺归属由 {@code UserContext} 过滤（多店共用一仓时取
     * 当前用户有权访问的那一行）。需要<b>店铺显式</b>扣减的场景（如调拨发出）用
     * {@link #decreaseInventory(Long, Long, String, Integer)}。
     */
    WarehouseInventory decreaseInventory(Long warehouseId, String sku, Integer quantity);

    /**
     * 扣减库存数量（店铺显式版：按 仓库+店铺+SKU 精确定位，不依赖 UserContext 过滤）。
     * <p>
     * 调拨发出这类「跨仓移动」必须用本方法：源仓行可能属于与当前请求不同的可见集合，
     * 用 UserContext 过滤会选错行或误判无库存。
     */
    WarehouseInventory decreaseInventory(Long warehouseId, Long shopId, String sku, Integer quantity);

    /**
     * 读某仓库+店铺+SKU 的当前账面数量（无记录返回 0）。
     * <p>
     * 供库存流水取 qty_before、盘点建单快照账面数使用。
     */
    int currentQuantity(Long warehouseId, Long shopId, String sku);
}
