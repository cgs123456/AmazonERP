package com.amz.service.impl;

import com.amz.context.UserContext;
import com.amz.exception.CodeErrorException;
import com.amz.mapper.WarehouseInventoryMapper;
import com.amz.mapper.WarehouseMapper;
import com.amz.model.Warehouse;
import com.amz.model.WarehouseInventory;
import com.amz.result.PageRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("海外仓服务租户隔离测试")
class WarehouseTenantIsolationTest {

    @Mock
    private WarehouseMapper warehouseMapper;

    @Mock
    private WarehouseInventoryMapper inventoryMapper;

    @InjectMocks
    private WarehouseServiceImpl service;

    @BeforeEach
    void authenticate() {
        UserContext.setUserId(9);
        UserContext.setShops(List.of(1L));
    }

    @AfterEach
    void clearUser() {
        UserContext.clear();
    }

    @Test
    @DisplayName("创建仓库：请求体 shopId 不属于当前账号时拒绝写入")
    void createWarehouseRejectsForeignShop() {
        Warehouse warehouse = new Warehouse();
        warehouse.setShopId(2L);

        assertThrows(CodeErrorException.class, () -> service.createWarehouse(warehouse));
        verify(warehouseMapper, never()).insert(any(Warehouse.class));
    }

    @Test
    @DisplayName("更新仓库：不能通过请求体把仓库改挂到其他店铺")
    void updateWarehouseCannotMoveShop() {
        Warehouse existing = new Warehouse();
        existing.setId(71L);
        existing.setShopId(1L);
        Warehouse request = new Warehouse();
        request.setId(71L);
        request.setShopId(2L);
        when(warehouseMapper.selectById(71L)).thenReturn(existing);

        assertThrows(CodeErrorException.class, () -> service.updateWarehouse(request));
        verify(warehouseMapper, never()).updateById(any(Warehouse.class));
    }

    @Test
    @DisplayName("更新仓库：他店仓库拒绝修改")
    void updateWarehouseRejectsForeignWarehouse() {
        Warehouse existing = new Warehouse();
        existing.setId(72L);
        existing.setShopId(2L);
        Warehouse request = new Warehouse();
        request.setId(72L);
        request.setShopId(2L);
        when(warehouseMapper.selectById(72L)).thenReturn(existing);

        assertThrows(CodeErrorException.class, () -> service.updateWarehouse(request));
        verify(warehouseMapper, never()).updateById(any(Warehouse.class));
    }

    @Test
    @DisplayName("库存查询：未提供 shopId 或查询他店时拒绝")
    void listInventoryRequiresAuthorizedShop() {
        assertThrows(CodeErrorException.class, () -> service.listInventory(1L, "SKU-1", null, PageRequest.first(50)));
        assertThrows(CodeErrorException.class, () -> service.listInventory(1L, "SKU-1", 2L, PageRequest.first(50)));
        verify(inventoryMapper, never()).selectList(any());
    }

    @Test
    @DisplayName("更新库位：他店库存记录拒绝修改")
    void updateLocationRejectsForeignInventory() {
        WarehouseInventory inventory = new WarehouseInventory();
        inventory.setId(81L);
        inventory.setShopId(2L);
        when(inventoryMapper.selectById(81L)).thenReturn(inventory);

        assertThrows(CodeErrorException.class,
                () -> service.updateLocationCode(81L, "A-01"));
        verify(inventoryMapper, never()).updateById(any(WarehouseInventory.class));
    }

    @Test
    @DisplayName("扣减库存：他店库存记录拒绝扣减")
    void decreaseInventoryRejectsForeignInventory() {
        WarehouseInventory inventory = new WarehouseInventory();
        inventory.setId(82L);
        inventory.setShopId(2L);
        when(inventoryMapper.selectList(any())).thenReturn(List.of(inventory));

        assertThrows(CodeErrorException.class,
                () -> service.decreaseInventory(1L, "SKU-1", 1));
        verify(inventoryMapper, never()).decreaseQuantityAtomic(any(), any());
    }
}
