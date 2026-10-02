package com.amz.service.impl;

import com.amz.mapper.OrderAuditRuleMapper;
import com.amz.mapper.OrderMapper;
import com.amz.mapper.OrderSplitLogMapper;
import com.amz.mapper.ShipmentRoutingMapper;
import com.amz.model.ShipmentRouting;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;

/**
 * 发货路由的「只给类型建议、不造仓库」契约。
 * <p>
 * <b>被修的缺陷</b>：旧实现把 {@code country + "-FBA-Warehouse"} 这种拼出来的仓名
 * {@code insert} 进 amz_shipment_routing，warehouseId 留空。路由表因此出现一批
 * 系统里根本不存在的仓库名，下游按 warehouse_name 找仓会找不到、按记录统计发货量会虚高。
 * 仓库主数据 amz_warehouse 属于物流模块，订单模块既无 mapper 也无 Feign 通道，
 * 所以这条链路能真实判定的只有仓库<b>类型</b>，具体仓库必须留给物流模块确认。
 */
@DisplayName("发货路由：不给不存在的仓库起名")
@ExtendWith(MockitoExtension.class)
class OrderAuditRoutingTest {

    @Mock
    private OrderAuditRuleMapper orderAuditRuleMapper;
    @Mock
    private OrderSplitLogMapper orderSplitLogMapper;
    @Mock
    private ShipmentRoutingMapper shipmentRoutingMapper;
    @Mock
    private OrderMapper orderMapper;

    @InjectMocks
    private OrderAuditServiceImpl service;

    @Test
    @DisplayName("FBA 覆盖国家：只写仓库类型，warehouseName/warehouseId 必须为空")
    void fbaCountryRecommendsTypeWithoutNamingAWarehouse() {
        ShipmentRouting routing = service.routeOrder(1L, "114-1111111-1111111", "SKU-1", "B0TEST01", 2, "US");
        ArgumentCaptor<ShipmentRouting> persisted = ArgumentCaptor.forClass(ShipmentRouting.class);
        verify(shipmentRoutingMapper).insert(persisted.capture());

        assertEquals("FBA", routing.getWarehouseType());
        assertNull(routing.getWarehouseName(), "不能再拼出 US-FBA-Warehouse 这种不存在的仓名");
        assertNull(routing.getWarehouseId());
        assertNull(persisted.getValue().getWarehouseName(), "入库的那一行同样不能带拼出来的仓名");
        assertTrue(routing.getSelectedReason().contains("未解析"),
                "原因里必须写清具体仓库未解析：" + routing.getSelectedReason());
    }

    @Test
    @DisplayName("非 FBA 国家走海外仓，同样不造仓名")
    void nonFbaCountryGoesOverseasWithoutNamingAWarehouse() {
        ShipmentRouting routing = service.routeOrder(1L, "114-2222222-2222222", "SKU-2", null, 1, "SG");
        assertEquals("OVERSEAS", routing.getWarehouseType());
        assertNull(routing.getWarehouseName());
        assertTrue(routing.getSelectedReason().contains("海外仓自发货"));
    }

    @Test
    @DisplayName("country 为 null 时不能凭空落到 FBA 分支，也不写仓名")
    void nullCountryFallsToOverseasWithoutAWarehouseName() {
        ShipmentRouting routing = service.routeOrder(1L, "114-3333333-3333333", "SKU-3", null, 1, null);
        assertEquals("OVERSEAS", routing.getWarehouseType());
        assertNull(routing.getWarehouseName());
    }
}
