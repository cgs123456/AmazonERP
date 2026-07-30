package com.amz.service.impl;

import com.amz.mapper.WarehouseInventoryMapper;
import com.amz.mapper.WarehouseMapper;
import com.amz.model.Warehouse;
import com.amz.model.WarehouseInventory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 海外仓服务单元测试（纯 Mockito，不依赖数据库）。
 * <p>
 * 验证仓库创建默认值、库存增减逻辑、库存不足异常处理等核心链路。
 * 库存增减已改为原子更新（decreaseQuantityAtomic / increaseQuantityAtomic），
 * 测试 mock 这些方法返回受影响行数以验证并发安全修复。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("海外仓服务单元测试")
class WarehouseServiceImplTest {

    @Mock
    private WarehouseMapper warehouseMapper;

    @Mock
    private WarehouseInventoryMapper inventoryMapper;

    @InjectMocks
    private WarehouseServiceImpl warehouseService;

    @Test
    @DisplayName("创建仓库 - 未指定状态/类型/已用容积 → 设置默认值")
    void testCreateWarehouseWithDefaults() {
        Warehouse wh = new Warehouse();
        wh.setShopId(1L);

        Warehouse result = warehouseService.createWarehouse(wh);

        assertEquals("ACTIVE", result.getStatus(), "默认状态应为 ACTIVE");
        assertEquals("THIRD_PARTY", result.getWarehouseType(), "默认类型应为 THIRD_PARTY");
        assertEquals(BigDecimal.ZERO, result.getUsedCbm(), "默认已用容积应为 0");
        verify(warehouseMapper).insert(wh);
    }

    @Test
    @DisplayName("创建仓库 - 已指定状态/类型 → 保留原值")
    void testCreateWarehousePreservesExistingValues() {
        Warehouse wh = new Warehouse();
        wh.setShopId(1L);
        wh.setStatus("INACTIVE");
        wh.setWarehouseType("FBA");
        wh.setUsedCbm(new BigDecimal("12.5"));

        Warehouse result = warehouseService.createWarehouse(wh);

        assertEquals("INACTIVE", result.getStatus());
        assertEquals("FBA", result.getWarehouseType());
        assertEquals(new BigDecimal("12.5"), result.getUsedCbm());
    }

    @Test
    @DisplayName("入库 - 数量 <= 0 → 抛出异常")
    void testIncreaseInventoryNonPositiveQuantity() {
        assertThrows(IllegalArgumentException.class,
                () -> warehouseService.increaseInventory(1L, 1L, "SKU-001", 0, "B1", "A1"));
        assertThrows(IllegalArgumentException.class,
                () -> warehouseService.increaseInventory(1L, 1L, "SKU-001", -5, "B1", "A1"));
        verify(inventoryMapper, never()).insert(any(WarehouseInventory.class));
    }

    @Test
    @DisplayName("入库 - 新库存（无同批次记录）→ insert 新记录")
    void testIncreaseInventoryNewRecord() {
        when(inventoryMapper.selectList(any())).thenReturn(Collections.emptyList());

        WarehouseInventory result = warehouseService.increaseInventory(
                1L, 10L, "SKU-001", 100, "BATCH-1", "LOC-A");

        assertEquals(100, result.getQuantity());
        assertEquals(0, result.getReservedQuantity());
        assertEquals(0, result.getInboundQuantity());
        assertEquals("BATCH-1", result.getBatchNo());
        assertEquals("LOC-A", result.getLocationCode());
        verify(inventoryMapper).insert(any(WarehouseInventory.class));
        verify(inventoryMapper, never()).updateById(any(WarehouseInventory.class));
    }

    @Test
    @DisplayName("入库 - 已有同批次库存 → 原子累加数量")
    void testIncreaseInventoryExistingRecord() {
        WarehouseInventory existing = new WarehouseInventory();
        existing.setId(1L);
        existing.setWarehouseId(1L);
        existing.setSku("SKU-001");
        existing.setQuantity(50);
        existing.setBatchNo("BATCH-1");
        WarehouseInventory afterIncrease = new WarehouseInventory();
        afterIncrease.setId(1L);
        afterIncrease.setQuantity(80);
        afterIncrease.setLocationCode("LOC-B");
        when(inventoryMapper.selectList(any())).thenReturn(List.of(existing));
        when(inventoryMapper.increaseQuantityAtomic(1L, 30)).thenReturn(1);
        when(inventoryMapper.selectById(1L)).thenReturn(afterIncrease);

        WarehouseInventory result = warehouseService.increaseInventory(
                1L, 10L, "SKU-001", 30, "BATCH-1", "LOC-B");

        assertEquals(80, result.getQuantity(), "50 + 30 = 80");
        assertEquals("LOC-B", result.getLocationCode(), "库位应更新为新值");
        verify(inventoryMapper).increaseQuantityAtomic(1L, 30);
        verify(inventoryMapper).updateById(afterIncrease);
        verify(inventoryMapper, never()).insert(any(WarehouseInventory.class));
    }

    @Test
    @DisplayName("出库 - 数量 <= 0 → 抛出异常")
    void testDecreaseInventoryNonPositiveQuantity() {
        assertThrows(IllegalArgumentException.class,
                () -> warehouseService.decreaseInventory(1L, "SKU-001", 0));
        assertThrows(IllegalArgumentException.class,
                () -> warehouseService.decreaseInventory(1L, "SKU-001", -1));
    }

    @Test
    @DisplayName("出库 - 库存不存在 → 抛出异常")
    void testDecreaseInventoryNotFound() {
        when(inventoryMapper.selectList(any())).thenReturn(Collections.emptyList());

        assertThrows(IllegalStateException.class,
                () -> warehouseService.decreaseInventory(1L, "SKU-001", 10));
    }

    @Test
    @DisplayName("出库 - 可用库存不足 → 抛出异常")
    void testDecreaseInventoryInsufficientStock() {
        WarehouseInventory inv = new WarehouseInventory();
        inv.setId(1L);
        inv.setQuantity(10);
        inv.setReservedQuantity(5);
        when(inventoryMapper.selectList(any())).thenReturn(List.of(inv));
        when(inventoryMapper.decreaseQuantityAtomic(1L, 10)).thenReturn(0);

        assertThrows(IllegalStateException.class,
                () -> warehouseService.decreaseInventory(1L, "SKU-001", 10),
                "可用库存 5 < 需求 10 应抛异常");
    }

    @Test
    @DisplayName("出库 - 正常扣减 → 原子扣减并返回最新库存")
    void testDecreaseInventoryNormal() {
        WarehouseInventory inv = new WarehouseInventory();
        inv.setId(1L);
        inv.setQuantity(100);
        inv.setReservedQuantity(20);
        WarehouseInventory afterDecrease = new WarehouseInventory();
        afterDecrease.setId(1L);
        afterDecrease.setQuantity(70);
        afterDecrease.setReservedQuantity(20);
        when(inventoryMapper.selectList(any())).thenReturn(List.of(inv));
        when(inventoryMapper.decreaseQuantityAtomic(1L, 30)).thenReturn(1);
        when(inventoryMapper.selectById(1L)).thenReturn(afterDecrease);

        WarehouseInventory result = warehouseService.decreaseInventory(1L, "SKU-001", 30);

        assertEquals(70, result.getQuantity(), "100 - 30 = 70");
        verify(inventoryMapper).decreaseQuantityAtomic(1L, 30);
        verify(inventoryMapper, never()).updateById(any(WarehouseInventory.class));
    }

    @Test
    @DisplayName("更新库位码 - 库存不存在 → 抛出异常")
    void testUpdateLocationCodeNotFound() {
        when(inventoryMapper.selectById(99L)).thenReturn(null);

        assertThrows(IllegalArgumentException.class,
                () -> warehouseService.updateLocationCode(99L, "LOC-X"));
    }

    @Test
    @DisplayName("更新库位码 - 正常更新 → 设置新库位并 updateById")
    void testUpdateLocationCodeNormal() {
        WarehouseInventory inv = new WarehouseInventory();
        inv.setId(1L);
        inv.setLocationCode("OLD-LOC");
        when(inventoryMapper.selectById(1L)).thenReturn(inv);

        WarehouseInventory result = warehouseService.updateLocationCode(1L, "NEW-LOC");

        assertEquals("NEW-LOC", result.getLocationCode());
        verify(inventoryMapper).updateById(inv);
    }
}