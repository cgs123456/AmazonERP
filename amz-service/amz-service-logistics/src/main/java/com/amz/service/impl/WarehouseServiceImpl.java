package com.amz.service.impl;

import com.amz.mapper.WarehouseInventoryMapper;
import com.amz.mapper.WarehouseMapper;
import com.amz.model.Warehouse;
import com.amz.model.WarehouseInventory;
import com.amz.service.WarehouseService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 海外仓服务实现。
 */
@Service
public class WarehouseServiceImpl implements WarehouseService {

    @Autowired
    private WarehouseMapper warehouseMapper;

    @Autowired
    private WarehouseInventoryMapper inventoryMapper;

    @Override
    public Warehouse createWarehouse(Warehouse warehouse) {
        if (warehouse.getStatus() == null) {
            warehouse.setStatus("ACTIVE");
        }
        if (warehouse.getWarehouseType() == null) {
            warehouse.setWarehouseType("THIRD_PARTY");
        }
        if (warehouse.getUsedCbm() == null) {
            warehouse.setUsedCbm(java.math.BigDecimal.ZERO);
        }
        warehouseMapper.insert(warehouse);
        return warehouse;
    }

    @Override
    public Warehouse updateWarehouse(Warehouse warehouse) {
        warehouseMapper.updateById(warehouse);
        return warehouse;
    }

    @Override
    public List<Warehouse> listWarehouses(Long shopId, String warehouseType) {
        LambdaQueryWrapper<Warehouse> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Warehouse::getShopId, shopId);
        if (warehouseType != null && !warehouseType.isBlank()) {
            wrapper.eq(Warehouse::getWarehouseType, warehouseType);
        }
        wrapper.orderByDesc(Warehouse::getId);
        return warehouseMapper.selectList(wrapper);
    }

    @Override
    public List<WarehouseInventory> listInventory(Long warehouseId, String sku, Long shopId) {
        LambdaQueryWrapper<WarehouseInventory> wrapper = new LambdaQueryWrapper<>();
        if (warehouseId != null) {
            wrapper.eq(WarehouseInventory::getWarehouseId, warehouseId);
        }
        if (shopId != null) {
            wrapper.eq(WarehouseInventory::getShopId, shopId);
        }
        if (sku != null && !sku.isBlank()) {
            wrapper.eq(WarehouseInventory::getSku, sku);
        }
        wrapper.orderByDesc(WarehouseInventory::getId);
        return inventoryMapper.selectList(wrapper);
    }

    @Override
    public WarehouseInventory updateLocationCode(Long inventoryId, String locationCode) {
        WarehouseInventory inv = inventoryMapper.selectById(inventoryId);
        if (inv == null) {
            throw new IllegalArgumentException("库存记录不存在：id=" + inventoryId);
        }
        inv.setLocationCode(locationCode);
        inventoryMapper.updateById(inv);
        return inv;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public WarehouseInventory increaseInventory(Long warehouseId, Long shopId, String sku,
                                                Integer quantity, String batchNo, String locationCode) {
        if (quantity == null || quantity <= 0) {
            throw new IllegalArgumentException("入库数量必须大于 0");
        }
        // 同仓库 + 同 SKU + 同批次 视为同一条库存记录（便于批次追溯）
        LambdaQueryWrapper<WarehouseInventory> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(WarehouseInventory::getWarehouseId, warehouseId)
                .eq(WarehouseInventory::getSku, sku)
                .eq(batchNo != null, WarehouseInventory::getBatchNo, batchNo);
        List<WarehouseInventory> existing = inventoryMapper.selectList(wrapper);
        if (existing.isEmpty()) {
            WarehouseInventory inv = new WarehouseInventory();
            inv.setWarehouseId(warehouseId);
            inv.setShopId(shopId);
            inv.setSku(sku);
            inv.setQuantity(quantity);
            inv.setReservedQuantity(0);
            inv.setInboundQuantity(0);
            inv.setBatchNo(batchNo);
            inv.setLocationCode(locationCode);
            inventoryMapper.insert(inv);
            return inv;
        }
        WarehouseInventory inv = existing.get(0);
        // 原子增加库存，避免「读-改-写」并发问题（高并发下多个入库同时读到旧值会互相覆盖）
        inventoryMapper.increaseQuantityAtomic(inv.getId(), quantity);
        // 重新查询最新库存（包含原子增加后的 quantity），再按需更新 locationCode，
        // 避免直接 updateById(existing) 覆盖刚原子更新的 quantity
        WarehouseInventory latest = inventoryMapper.selectById(inv.getId());
        if (latest != null && locationCode != null) {
            latest.setLocationCode(locationCode);
            inventoryMapper.updateById(latest);
        }
        return latest != null ? latest : inv;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public WarehouseInventory decreaseInventory(Long warehouseId, String sku, Integer quantity) {
        if (quantity == null || quantity <= 0) {
            throw new IllegalArgumentException("出库数量必须大于 0");
        }
        LambdaQueryWrapper<WarehouseInventory> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(WarehouseInventory::getWarehouseId, warehouseId)
                .eq(WarehouseInventory::getSku, sku);
        List<WarehouseInventory> list = inventoryMapper.selectList(wrapper);
        if (list.isEmpty()) {
            throw new IllegalStateException("库存不足：warehouseId=" + warehouseId + " sku=" + sku);
        }
        WarehouseInventory inv = list.get(0);
        // 原子扣减：SQL 行级锁保证不超卖，受影响行数=0 表示可用库存不足
        int affected = inventoryMapper.decreaseQuantityAtomic(inv.getId(), quantity);
        if (affected == 0) {
            throw new IllegalStateException("库存不足：warehouseId=" + warehouseId + " sku=" + sku + " need=" + quantity);
        }
        // 返回扣减后的最新库存
        return inventoryMapper.selectById(inv.getId());
    }
}