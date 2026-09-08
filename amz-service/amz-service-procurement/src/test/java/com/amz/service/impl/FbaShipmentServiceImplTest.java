package com.amz.service.impl;

import com.amz.context.UserContext;
import com.amz.mapper.FbaShipmentItemMapper;
import com.amz.mapper.FbaShipmentMapper;
import com.amz.mapper.InventoryBatchMapper;
import com.amz.model.FbaShipment;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * FBA 货件服务多租户越权测试（纯 Mockito，不依赖数据库）。
 * <p>
 * 回归：按 ID 查询/操作货件曾无店铺归属校验，且对应端点仅携带 ID、
 * ShopScoped 切面按参数名找不到 shopId 而跳过，形成 IDOR。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("FBA 货件多租户越权测试")
class FbaShipmentServiceImplTest {

    @Mock
    private FbaShipmentMapper fbaShipmentMapper;

    @Mock
    private FbaShipmentItemMapper fbaShipmentItemMapper;

    @Mock
    private InventoryBatchMapper inventoryBatchMapper;

    @InjectMocks
    private FbaShipmentServiceImpl shipmentService;

    @AfterEach
    void cleanup() {
        UserContext.clear();
    }

    private static FbaShipment shipment(long shopId) {
        FbaShipment s = new FbaShipment();
        s.setId(7L);
        s.setShopId(shopId);
        return s;
    }

    @Test
    @DisplayName("getShipment：非授权店铺应拒绝")
    void getShipmentDeniedForOtherShop() {
        UserContext.setShops(List.of(1L));
        when(fbaShipmentMapper.selectById(7L)).thenReturn(shipment(99L));

        assertThrows(IllegalStateException.class, () -> shipmentService.getShipment(7L));
    }

    @Test
    @DisplayName("getShipment：授权店铺放行")
    void getShipmentAllowedForOwnShop() {
        UserContext.setShops(List.of(1L));
        when(fbaShipmentMapper.selectById(7L)).thenReturn(shipment(1L));

        assertEquals(1L, shipmentService.getShipment(7L).getShopId());
    }

    @Test
    @DisplayName("getShipment：无授权上下文（内部调用）放行，与切面 fail-open 语义一致")
    void getShipmentAllowedWithoutContext() {
        when(fbaShipmentMapper.selectById(7L)).thenReturn(shipment(99L));

        assertEquals(99L, shipmentService.getShipment(7L).getShopId());
    }

    @Test
    @DisplayName("updateShipment：请求体 shopId 不得搬移货件归属")
    void updateShipmentLocksShopId() {
        UserContext.setShops(List.of(1L));
        when(fbaShipmentMapper.selectById(7L)).thenReturn(shipment(1L));

        FbaShipment input = shipment(99L); // 伪造归属
        shipmentService.updateShipment(input);

        assertEquals(1L, input.getShopId());
    }

    @Test
    @DisplayName("processReceipt：空明细/缺字段/负数直接参数异常，而非 NPE 穿透 500")
    void processReceiptValidatesItems() {
        UserContext.setShops(List.of(1L));
        when(fbaShipmentMapper.selectById(7L)).thenReturn(shipment(1L));
        when(fbaShipmentItemMapper.selectList(any())).thenReturn(List.of());

        assertThrows(com.amz.exception.AttrIsNullException.class,
                () -> shipmentService.processReceipt(7L, null));
        assertThrows(com.amz.exception.AttrIsNullException.class,
                () -> shipmentService.processReceipt(7L, List.of()));
        assertThrows(com.amz.exception.AttrIsNullException.class,
                () -> shipmentService.processReceipt(7L, List.of(Map.of("itemId", 1))));

        Map<String, Object> negative = new java.util.HashMap<>();
        negative.put("itemId", 1);
        negative.put("receivedQty", -5);
        assertThrows(com.amz.exception.AttrIsNullException.class,
                () -> shipmentService.processReceipt(7L, List.of(negative)));
    }
}
