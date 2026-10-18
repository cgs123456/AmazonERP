package com.amz.service.impl;

import com.amz.exception.CodeErrorException;
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
import static org.junit.jupiter.api.Assertions.assertTrue;
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

    /**
     * §S33.105：同仓同店同 SKU 同批次却查到多行时，必须失败而不是取第一行。
     *
     * <p>旧实现 {@code existing.get(0)} 只增加那一行，其余行的库存永远不涨
     * ——账实不符且无任何信号（变异验证：把 get(0) 换成 get(size-1)，
     * 原有 11 项测试全绿，即「取哪一行」根本没人守）。
     *
     * <p>表上没有任何唯一键，而 MySQL 唯一键又不约束 NULL，所以
     * {@code batch_no 为 NULL} 时多行是合法的——这是数据问题，只能显式暴露。
     */
    @Test
    @DisplayName("入库 - 同批次查到多行库存 → 显式失败，不静默只加第一行")
    void testIncreaseInventoryRejectsDuplicateRows() {
        WarehouseInventory first = new WarehouseInventory();
        first.setId(1L);
        first.setWarehouseId(1L);
        first.setSku("SKU-001");
        first.setQuantity(50);
        first.setBatchNo(null);           // batch_no 为 NULL：唯一键约束不到这一种
        WarehouseInventory second = new WarehouseInventory();
        second.setId(2L);
        second.setWarehouseId(1L);
        second.setSku("SKU-001");
        second.setQuantity(7);
        second.setBatchNo(null);
        when(inventoryMapper.selectList(any())).thenReturn(List.of(first, second));

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> warehouseService.increaseInventory(1L, 10L, "SKU-001", 30, null, "LOC-A"));

        assertTrue(e.getMessage().contains("不唯一"),
                "理由要能看出是重复记录问题，实际=" + e.getMessage());
        // 一行都不该写：任何一行被增加都会让另一行继续落后
        verify(inventoryMapper, never()).increaseQuantityAtomic(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyInt());
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

    /**
     * §S33.107：FIFO 跨批扣减。
     *
     * <p>旧实现只试 {@code get(0)} 那一行：本例 id=1 只够出 5、id=2 够出 7，
     * 合计 12 ≥ 需求 10，但旧实现会在第一行就报「库存不足」——
     * 一个凭空出现的假失败，把能发的货挡下来。
     */
    @Test
    @DisplayName("出库 - 单批不足但多批合计够 → 跨批扣减，不报库存不足")
    void testDecreaseInventoryAcrossBatches() {
        WarehouseInventory first = new WarehouseInventory();
        first.setId(1L);
        first.setQuantity(5);
        first.setReservedQuantity(0);
        first.setAvailableQuantity(5);
        WarehouseInventory second = new WarehouseInventory();
        second.setId(2L);
        second.setQuantity(7);
        second.setReservedQuantity(0);
        second.setAvailableQuantity(7);
        WarehouseInventory afterSecond = new WarehouseInventory();
        afterSecond.setId(2L);
        afterSecond.setQuantity(2);          // 7 - 5 = 2

        // 打乱顺序：实现必须自己按 id 升序排，不能依赖查询返回顺序
        when(inventoryMapper.selectList(any())).thenReturn(List.of(second, first));
        // 第一批（id=1）整笔 10 不够；按可用量 5 扣成功
        when(inventoryMapper.decreaseQuantityAtomic(1L, 10)).thenReturn(0);
        when(inventoryMapper.decreaseQuantityAtomic(1L, 5)).thenReturn(1);
        // 第二批（id=2）扣剩余的 5
        when(inventoryMapper.decreaseQuantityAtomic(2L, 5)).thenReturn(1);
        when(inventoryMapper.selectById(2L)).thenReturn(afterSecond);

        WarehouseInventory result = warehouseService.decreaseInventory(1L, 10L, "SKU-001", 10);

        assertEquals(2, result.getQuantity(), "id=1 出 5、id=2 出 5，第二行剩 2");
        verify(inventoryMapper).decreaseQuantityAtomic(1L, 5);
        verify(inventoryMapper).decreaseQuantityAtomic(2L, 5);
    }

    /**
     * 与上一例互为边界：所有批合计仍不够时必须失败，
     * 且不能留下「扣了一部分」的残局（由 @Transactional 回滚，这里只验行为）。
     */
    @Test
    @DisplayName("出库 - 多批合计仍不足 → 失败，不部分扣减")
    void testDecreaseInventoryFailsWhenTotalInsufficient() {
        WarehouseInventory first = new WarehouseInventory();
        first.setId(1L);
        first.setQuantity(3);
        first.setReservedQuantity(0);
        first.setAvailableQuantity(3);
        WarehouseInventory second = new WarehouseInventory();
        second.setId(2L);
        second.setQuantity(2);
        second.setReservedQuantity(0);
        second.setAvailableQuantity(2);
        when(inventoryMapper.selectList(any())).thenReturn(List.of(first, second));
        // 合计 5 < 需求 10：每一批最多能出自己的可用量，全部扣完仍不够
        when(inventoryMapper.decreaseQuantityAtomic(1L, 10)).thenReturn(0);
        when(inventoryMapper.decreaseQuantityAtomic(1L, 3)).thenReturn(1);
        when(inventoryMapper.decreaseQuantityAtomic(2L, 7)).thenReturn(0);
        when(inventoryMapper.decreaseQuantityAtomic(2L, 2)).thenReturn(1);

        assertThrows(IllegalStateException.class,
                () -> warehouseService.decreaseInventory(1L, 10L, "SKU-001", 10),
                "合计 5 < 10 应失败");
    }

    @Test
    @DisplayName("更新库位码 - 库存不存在 → 抛出异常")
    void testUpdateLocationCodeNotFound() {
        when(inventoryMapper.selectById(99L)).thenReturn(null);

        CodeErrorException error = assertThrows(CodeErrorException.class,
                () -> warehouseService.updateLocationCode(99L, "LOC-X"));
        assertEquals("库存记录不存在或无权访问", error.getMessage());
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