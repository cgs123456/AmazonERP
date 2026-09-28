package com.amz.service.impl;

import com.amz.context.UserContext;
import com.amz.exception.CodeErrorException;
import com.amz.mapper.OutboundOrderMapper;
import com.amz.model.OutboundOrder;
import com.amz.service.WarehouseService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("出库服务租户隔离与状态机测试")
class OutboundServiceImplTest {

    @Mock
    private OutboundOrderMapper outboundOrderMapper;

    @Mock
    private WarehouseService warehouseService;

    @InjectMocks
    private OutboundServiceImpl service;

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
    @DisplayName("创建：请求体 shopId 不属于当前账号时拒绝写入")
    void createRejectsForeignShop() {
        OutboundOrder order = order(2L, "PENDING");

        CodeErrorException e = assertThrows(CodeErrorException.class,
                () -> service.createOutboundOrder(order));

        assertTrue(e.getMessage().contains("不属于当前账号"), e.getMessage());
        verify(outboundOrderMapper, never()).insert(any(OutboundOrder.class));
    }

    @Test
    @DisplayName("创建：客户端不能绕过流程直接指定 SHIPPED，必须从 PENDING 开始")
    void createAlwaysStartsPending() {
        OutboundOrder order = order(1L, "SHIPPED");

        OutboundOrder saved = service.createOutboundOrder(order);

        assertEquals("PENDING", saved.getStatus());
        verify(outboundOrderMapper).insert(order);
    }

    @Test
    @DisplayName("按 ID 操作：他店出库单的 pick/pack/ship/cancel 全部拒绝")
    void idOperationsRejectForeignOrder() {
        OutboundOrder order = order(2L, "PENDING");
        order.setId(51L);
        when(outboundOrderMapper.selectById(51L)).thenReturn(order);

        assertThrows(CodeErrorException.class, () -> service.pickOutbound(51L));
        assertThrows(CodeErrorException.class, () -> service.packOutbound(51L));
        assertThrows(CodeErrorException.class, () -> service.shipOutbound(51L, "UPS", "TRK", Collections.emptyList()));
        assertThrows(CodeErrorException.class, () -> service.cancelOutbound(51L));

        verify(outboundOrderMapper, never()).updateById(any(OutboundOrder.class));
        verify(warehouseService, never()).decreaseInventory(any(), any(), any());
    }

    @Test
    @DisplayName("状态机：非法流转返回业务异常而不是服务器内部错误")
    void illegalTransitionUsesBusinessException() {
        OutboundOrder order = order(1L, "PACKED");
        order.setId(52L);
        when(outboundOrderMapper.selectById(52L)).thenReturn(order);

        assertThrows(CodeErrorException.class, () -> service.pickOutbound(52L));
        verify(outboundOrderMapper, never()).updateById(any(OutboundOrder.class));
    }

    @Test
    @DisplayName("状态机：已打包的出库单不可取消")
    void cancelRejectsPackedOrder() {
        OutboundOrder order = order(1L, "PACKED");
        order.setId(53L);
        when(outboundOrderMapper.selectById(53L)).thenReturn(order);

        assertThrows(CodeErrorException.class, () -> service.cancelOutbound(53L));
        verify(outboundOrderMapper, never()).updateById(any(OutboundOrder.class));
    }

    private OutboundOrder order(Long shopId, String status) {
        OutboundOrder order = new OutboundOrder();
        order.setShopId(shopId);
        order.setWarehouseId(1L);
        order.setStatus(status);
        order.setTotalItems(10);
        return order;
    }
}
