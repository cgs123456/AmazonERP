package com.amz.service.impl;

import com.amz.context.UserContext;
import com.amz.mapper.FbaShipmentMapper;
import com.amz.mapper.InventoryBatchMapper;
import com.amz.model.FbaShipment;
import com.amz.model.InventoryBatch;
import com.amz.result.PageRequest;
import com.amz.result.PageResult;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("FBA 货件列表游标分页测试")
class FbaShipmentServiceImplPagingTest {

    @Mock
    private FbaShipmentMapper fbaShipmentMapper;

    @Mock
    private InventoryBatchMapper inventoryBatchMapper;

    @InjectMocks
    private FbaShipmentServiceImpl service;

    @BeforeAll
    static void initMybatisTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, FbaShipment.class);
        TableInfoHelper.initTableInfo(assistant, InventoryBatch.class);
    }

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
    @DisplayName("货件列表：探测行不返回，cursor 取本页最后一条可见行")
    void truncatedPageDropsProbeRowAndUsesLastVisibleCursor() {
        when(fbaShipmentMapper.selectList(any())).thenReturn(List.of(
                shipment(40L), shipment(39L), shipment(38L)));

        PageResult<FbaShipment> page =
                service.listShipments(1L, "SHIPPED", PageRequest.first(2));

        assertEquals(List.of(40L, 39L), page.items().stream().map(FbaShipment::getId).toList());
        assertTrue(page.truncated());
        assertEquals(PageRequest.encodeCursor(39L), page.nextCursor());
    }

    @Test
    @DisplayName("货件列表：未取满一页时不误报截断")
    void untruncatedPageHasNoCursor() {
        when(fbaShipmentMapper.selectList(any())).thenReturn(List.of(shipment(2L)));

        PageResult<FbaShipment> page = service.listShipments(1L, null, PageRequest.first(50));

        assertEquals(1, page.items().size());
        assertFalse(page.truncated());
        assertNull(page.nextCursor());
    }

    @Test
    @DisplayName("货件列表：status 与 id 游标同时生效，并带显式 LIMIT")
    @SuppressWarnings({"unchecked", "rawtypes"})
    void cursorAndStatusUseKeysetWithExplicitLimit() {
        when(fbaShipmentMapper.selectList(any())).thenReturn(List.of(shipment(5L)));
        PageRequest request = PageRequest.of(10, PageRequest.encodeCursor(900L));

        service.listShipments(1L, "SHIPPED", request);

        ArgumentCaptor<LambdaQueryWrapper<FbaShipment>> captor =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(fbaShipmentMapper).selectList(captor.capture());
        LambdaQueryWrapper<FbaShipment> wrapper = captor.getValue();
        String segment = wrapper.getCustomSqlSegment();
        assertTrue(segment.contains("LIMIT"), "货件列表必须禁止无界 selectList：" + segment);
        assertTrue(wrapper.getParamNameValuePairs().containsValue(900L));
        assertTrue(wrapper.getParamNameValuePairs().containsValue("SHIPPED"));
    }

    @Test
    @DisplayName("库存批次列表：按 FIFO 复合键游标分页，探测行不返回")
    void batchListUsesCompositeFifoPage() {
        when(inventoryBatchMapper.selectList(any())).thenReturn(List.of(
                inventoryBatch(1L, "2026-09-01"),
                inventoryBatch(2L, "2026-09-01"),
                inventoryBatch(3L, "2026-09-02")));

        PageResult<InventoryBatch> page =
                service.listBatchesBySku(1L, "SKU-1", PageRequest.first(2));

        assertEquals(List.of(1L, 2L), page.items().stream().map(InventoryBatch::getId).toList());
        assertTrue(page.truncated());
        assertEquals(PageRequest.encodeCursor("2026-09-01|2"), page.nextCursor());
    }

    @Test
    @DisplayName("库存批次列表：游标同时约束 inbound_date 与 id，且必须有显式 LIMIT")
    @SuppressWarnings({"unchecked", "rawtypes"})
    void batchCursorUsesDateAndIdTieBreak() {
        when(inventoryBatchMapper.selectList(any())).thenReturn(List.of());
        PageRequest request = PageRequest.of(10, PageRequest.encodeCursor("2026-09-01|77"));

        service.listBatchesBySku(1L, "SKU-1", request);

        ArgumentCaptor<LambdaQueryWrapper<InventoryBatch>> captor =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(inventoryBatchMapper).selectList(captor.capture());
        LambdaQueryWrapper<InventoryBatch> wrapper = captor.getValue();
        String segment = wrapper.getCustomSqlSegment();
        assertTrue(segment.contains("inbound_date"), segment);
        assertTrue(segment.contains("id"), segment);
        assertTrue(segment.contains("LIMIT"), "库存批次列表必须禁止无界 selectList：" + segment);
        assertTrue(wrapper.getParamNameValuePairs().containsValue(LocalDate.of(2026, 9, 1)));
        assertTrue(wrapper.getParamNameValuePairs().containsValue(77L));
    }

    private static InventoryBatch inventoryBatch(long id, String inboundDate) {
        InventoryBatch batch = new InventoryBatch();
        batch.setId(id);
        batch.setShopId(1L);
        batch.setSku("SKU-1");
        batch.setBatchNo("BAT-" + id);
        batch.setQuantity(10);
        batch.setAvailableQuantity(10);
        batch.setUnitCost(new BigDecimal("10.00"));
        batch.setInboundDate(LocalDate.parse(inboundDate));
        batch.setStatus("ACTIVE");
        return batch;
    }

    private static FbaShipment shipment(Long id) {
        FbaShipment shipment = new FbaShipment();
        shipment.setId(id);
        shipment.setShopId(1L);
        shipment.setStatus("SHIPPED");
        return shipment;
    }
}