package com.amz.service.impl;

import com.amz.client.LogisticsTrackingClient;
import com.amz.context.UserContext;
import com.amz.exception.CodeErrorException;
import com.amz.mapper.ShipmentMapper;
import com.amz.mapper.TrackingEventMapper;
import com.amz.model.Shipment;
import com.amz.result.PageRequest;
import com.amz.service.TrackingIngestService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("货件服务租户隔离与创建状态测试")
class LogisticsServiceImplTenantTest {

    @Mock
    private ShipmentMapper shipmentMapper;

    @Mock
    private TrackingEventMapper trackingEventMapper;

    @Mock
    private LogisticsTrackingClient trackingClient;

    @Mock
    private TrackingIngestService trackingIngestService;

    @InjectMocks
    private LogisticsServiceImpl service;

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
    @DisplayName("创建货件：请求体 shopId 不属于当前账号时拒绝写入")
    void createShipmentRejectsForeignShop() {
        Shipment shipment = new Shipment();
        shipment.setShopId(2L);

        assertThrows(CodeErrorException.class, () -> service.createShipment(shipment));
        verify(shipmentMapper, never()).insert(any(Shipment.class));
    }

    @Test
    @DisplayName("创建货件：客户端不能直接指定终态，必须从 CREATED 开始")
    void createShipmentAlwaysStartsCreated() {
        Shipment shipment = new Shipment();
        shipment.setShopId(1L);
        shipment.setStatus("DELIVERED");

        Shipment saved = service.createShipment(shipment);

        assertEquals("CREATED", saved.getStatus());
        verify(shipmentMapper).insert(shipment);
    }

    @Test
    @DisplayName("货件列表：未提供 shopId 或查询他店时拒绝")
    void listShipmentsRequiresAuthorizedShop() {
        assertThrows(CodeErrorException.class, () -> service.listShipments(null, null, PageRequest.first(50)));
        assertThrows(CodeErrorException.class, () -> service.listShipments(2L, null, PageRequest.first(50)));
        verify(shipmentMapper, never()).selectList(any());
    }

    @Test
    @DisplayName("轨迹时间线：先校验货件归属，越权时不得查询轨迹")
    void trackingTimelineChecksTenantBeforeTimelineQuery() {
        Shipment foreign = new Shipment();
        foreign.setId(99L);
        foreign.setShopId(2L);
        when(shipmentMapper.selectById(99L)).thenReturn(foreign);

        assertThrows(CodeErrorException.class,
                () -> service.getTrackingTimeline(99L, PageRequest.first(50)));

        verify(trackingEventMapper, never()).selectList(any());
    }
}