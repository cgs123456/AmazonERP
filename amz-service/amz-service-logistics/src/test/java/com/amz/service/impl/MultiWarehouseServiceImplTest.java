package com.amz.service.impl;

import com.amz.context.UserContext;
import com.amz.exception.CodeErrorException;
import com.amz.mapper.InventoryAlertMapper;
import com.amz.mapper.WarehouseStockMapper;
import com.amz.model.InventoryAlert;
import com.amz.model.WarehouseStock;
import com.amz.result.PageRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("多仓库存服务租户隔离测试")
class MultiWarehouseServiceImplTest {

    @Mock
    private WarehouseStockMapper warehouseStockMapper;

    @Mock
    private InventoryAlertMapper inventoryAlertMapper;

    @InjectMocks
    private MultiWarehouseServiceImpl service;

    @BeforeEach
    void authenticate() {
        UserContext.setUserId(9);
        UserContext.setShops(java.util.List.of(1L));
    }

    @AfterEach
    void clearUser() {
        UserContext.clear();
    }

    @Test
    @DisplayName("保存库存快照：请求体 shopId 不属于当前账号时拒绝写入")
    void saveStockRejectsForeignShop() {
        WarehouseStock stock = new WarehouseStock();
        stock.setShopId(2L);
        stock.setWarehouseId(1L);
        stock.setSku("SKU-1");

        assertThrows(CodeErrorException.class, () -> service.saveStock(stock));
        verify(warehouseStockMapper, never()).insert(any(WarehouseStock.class));
        verify(warehouseStockMapper, never()).updateById(any(WarehouseStock.class));
    }

    @Test
    @DisplayName("创建预警：请求体 shopId 不属于当前账号时拒绝写入")
    void createAlertRejectsForeignShop() {
        InventoryAlert alert = new InventoryAlert();
        alert.setShopId(2L);
        alert.setSku("SKU-1");
        alert.setAlertType("LOW_STOCK");

        assertThrows(CodeErrorException.class, () -> service.createAlert(alert));
        verify(inventoryAlertMapper, never()).insert(any(InventoryAlert.class));
    }

    @Test
    @DisplayName("切换预警：他店规则拒绝修改")
    void toggleAlertRejectsForeignAlert() {
        InventoryAlert alert = new InventoryAlert();
        alert.setId(61L);
        alert.setShopId(2L);
        when(inventoryAlertMapper.selectById(61L)).thenReturn(alert);

        assertThrows(CodeErrorException.class, () -> service.toggleAlert(61L, false));
        verify(inventoryAlertMapper, never()).updateById(any(InventoryAlert.class));
    }

    @Test
    @DisplayName("切换预警：规则不存在时返回业务异常而不是静默成功")
    void toggleAlertRejectsMissingAlert() {
        when(inventoryAlertMapper.selectById(99L)).thenReturn(null);

        assertThrows(CodeErrorException.class, () -> service.toggleAlert(99L, false));
        verify(inventoryAlertMapper, never()).updateById(any(InventoryAlert.class));
    }

    @Test
    @DisplayName("库存查询：未提供 shopId 或查询他店时拒绝，避免跨店读取")
    void listStockRequiresAuthorizedShop() {
        assertThrows(CodeErrorException.class, () -> service.listStock(null, null, null, PageRequest.first(50)));
        assertThrows(CodeErrorException.class, () -> service.listStock(2L, null, null, PageRequest.first(50)));
        verify(warehouseStockMapper, never()).selectList(any());
    }
}
