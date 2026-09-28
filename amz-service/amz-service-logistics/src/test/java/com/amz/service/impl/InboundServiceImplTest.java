package com.amz.service.impl;

import com.amz.context.UserContext;
import com.amz.exception.CodeErrorException;
import com.amz.mapper.InboundOrderMapper;
import com.amz.model.InboundOrder;
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
@DisplayName("入库服务租户隔离与状态机测试")
class InboundServiceImplTest {

    @Mock
    private InboundOrderMapper inboundOrderMapper;

    @Mock
    private WarehouseService warehouseService;

    @InjectMocks
    private InboundServiceImpl service;

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
        InboundOrder order = order(2L, "PENDING");

        CodeErrorException e = assertThrows(CodeErrorException.class,
                () -> service.createInboundOrder(order));

        assertTrue(e.getMessage().contains("不属于当前账号"), e.getMessage());
        verify(inboundOrderMapper, never()).insert(any(InboundOrder.class));
    }

    @Test
    @DisplayName("创建：客户端不能绕过流程直接指定 RECEIVED，必须从 PENDING 开始")
    void createAlwaysStartsPending() {
        InboundOrder order = order(1L, "RECEIVED");

        InboundOrder saved = service.createInboundOrder(order);

        assertEquals("PENDING", saved.getStatus());
        verify(inboundOrderMapper).insert(order);
    }

    @Test
    @DisplayName("按 ID 操作：他店入库单的 transit/receive/cancel 全部拒绝")
    void idOperationsRejectForeignOrder() {
        InboundOrder order = order(2L, "PENDING");
        order.setId(41L);
        when(inboundOrderMapper.selectById(41L)).thenReturn(order);

        assertThrows(CodeErrorException.class, () -> service.transitInbound(41L));
        assertThrows(CodeErrorException.class, () -> service.receiveInbound(41L, Collections.emptyList()));
        assertThrows(CodeErrorException.class, () -> service.cancelInbound(41L));

        verify(inboundOrderMapper, never()).updateById(any(InboundOrder.class));
        verify(warehouseService, never()).increaseInventory(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("状态机：非法流转返回业务异常而不是服务器内部错误")
    void illegalTransitionUsesBusinessException() {
        InboundOrder order = order(1L, "RECEIVED");
        order.setId(42L);
        when(inboundOrderMapper.selectById(42L)).thenReturn(order);

        assertThrows(CodeErrorException.class, () -> service.transitInbound(42L));
        verify(inboundOrderMapper, never()).updateById(any(InboundOrder.class));
    }

    @Test
    @DisplayName("状态机：运输中的入库单不可取消")
    void cancelRejectsInTransit() {
        InboundOrder order = order(1L, "IN_TRANSIT");
        order.setId(43L);
        when(inboundOrderMapper.selectById(43L)).thenReturn(order);

        assertThrows(CodeErrorException.class, () -> service.cancelInbound(43L));
        verify(inboundOrderMapper, never()).updateById(any(InboundOrder.class));
    }

    private InboundOrder order(Long shopId, String status) {
        InboundOrder order = new InboundOrder();
        order.setShopId(shopId);
        order.setWarehouseId(1L);
        order.setStatus(status);
        order.setTotalItems(10);
        return order;
    }
}
