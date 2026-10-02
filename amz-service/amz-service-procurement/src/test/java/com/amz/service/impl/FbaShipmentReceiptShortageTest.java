package com.amz.service.impl;

import com.amz.context.UserContext;
import com.amz.dto.ReceiptShortage;
import com.amz.exception.CodeErrorException;
import com.amz.mapper.FbaShipmentItemMapper;
import com.amz.mapper.FbaShipmentMapper;
import com.amz.model.FbaShipment;
import com.amz.model.FbaShipmentItem;
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
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 入库短收清单测试：这条查询是「到货差异 → 费用差异」闭环的事实来源。
 * <p>
 * 过去 processReceipt 检测到短收只写一条 warn 日志，该赔的钱停在日志里没人追。
 * 这里要保证三件事：短收行真的被捞出来（含亚马逊侧货件号，财务差异表认它不是内部 ID）、
 * 没有亚马逊货件号的货件不参与、越权店铺直接拒绝而不是返回空列表假装没有数据。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("入库短收待登记清单")
class FbaShipmentReceiptShortageTest {

    @Mock
    private FbaShipmentMapper fbaShipmentMapper;

    @Mock
    private FbaShipmentItemMapper fbaShipmentItemMapper;

    @InjectMocks
    private FbaShipmentServiceImpl service;

    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, FbaShipment.class);
        TableInfoHelper.initTableInfo(assistant, FbaShipmentItem.class);
    }

    @BeforeEach
    void authorize() {
        UserContext.setUserId(9);
        UserContext.setShops(List.of(1L));
    }

    @AfterEach
    void clear() {
        UserContext.clear();
    }

    private FbaShipment shipment(long id, String fbaShipmentId) {
        FbaShipment s = new FbaShipment();
        s.setId(id);
        s.setShopId(1L);
        s.setShipmentNo("FBA-SHIP-" + id);
        s.setFbaShipmentId(fbaShipmentId);
        s.setStatus("SHIPPED");
        return s;
    }

    private FbaShipmentItem item(long id, long shipmentId, String sku, int expected, int received) {
        FbaShipmentItem i = new FbaShipmentItem();
        i.setId(id);
        i.setFbaShipmentId(shipmentId);
        i.setSku(sku);
        i.setAsin("B0" + sku);
        i.setQuantity(expected);
        i.setReceivedQuantity(received);
        i.setUnitCost(new BigDecimal("12.50"));
        i.setTotalCost(new BigDecimal("125.00"));
        return i;
    }

    @SuppressWarnings("unchecked")
    private LambdaQueryWrapper<FbaShipmentItem> captureItemWrapper() {
        ArgumentCaptor<LambdaQueryWrapper<FbaShipmentItem>> captor =
                (ArgumentCaptor) ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(fbaShipmentItemMapper).selectList(captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("短收行被捞出并带上亚马逊侧货件号、短收件数与我方成本口径")
    void mapsShortageRows() {
        when(fbaShipmentMapper.selectList(any()))
                .thenReturn(List.of(shipment(31L, "FBA18ABC")));
        when(fbaShipmentItemMapper.selectList(any())).thenReturn(List.of(
                item(41L, 31L, "SKU-1", 100, 88),
                item(42L, 31L, "SKU-2", 50, 49)));

        PageResult<ReceiptShortage> page = service.listReceiptShortages(1L, PageRequest.first(50));

        assertEquals(2, page.items().size());
        ReceiptShortage first = page.items().get(0);
        assertEquals("FBA18ABC", first.getFbaShipmentId());
        assertEquals("FBA-SHIP-31", first.getShipmentNo());
        assertEquals(100, first.getExpectedQty());
        assertEquals(88, first.getReceivedQty());
        assertEquals(12, first.getShortUnits());
        assertEquals(new BigDecimal("12.50"), first.getUnitCost());
        assertEquals(41L, first.getItemId());
    }

    @Test
    @DisplayName("SQL 必须同时限定「已签收」与「实发<应发」，且排除没有亚马逊货件号的货件")
    void filtersShortagesInSql() {
        when(fbaShipmentMapper.selectList(any())).thenReturn(List.of(shipment(31L, "FBA1")));
        when(fbaShipmentItemMapper.selectList(any())).thenReturn(Collections.emptyList());

        service.listReceiptShortages(1L, PageRequest.first(50));

        String itemSql = captureItemWrapper().getSqlSegment();
        assertTrue(itemSql.contains("received_quantity < quantity"), itemSql);
        assertTrue(itemSql.toUpperCase().contains("RECEIVED_QUANTITY IS NOT NULL"), itemSql);
        ArgumentCaptor<LambdaQueryWrapper<FbaShipment>> sc =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(fbaShipmentMapper).selectList(sc.capture());
        assertTrue(sc.getValue().getSqlSegment().toUpperCase().contains("FBA_SHIPMENT_ID IS NOT NULL"),
                sc.getValue().getSqlSegment());
    }

    @Test
    @DisplayName("带游标时按明细 id 倒序翻页，且仍带 LIMIT（不允许无界拉取）")
    void appliesItemCursor() {
        when(fbaShipmentMapper.selectList(any())).thenReturn(List.of(shipment(31L, "FBA1")));
        when(fbaShipmentItemMapper.selectList(any())).thenReturn(Collections.emptyList());

        service.listReceiptShortages(1L, PageRequest.of(20, PageRequest.encodeCursor(41L)));

        LambdaQueryWrapper<FbaShipmentItem> wrapper = captureItemWrapper();
        assertTrue(wrapper.getSqlSegment().contains("id <"), wrapper.getSqlSegment());
        assertTrue(wrapper.getSqlSegment().contains("LIMIT"), wrapper.getSqlSegment());
        Map<String, Object> values = wrapper.getParamNameValuePairs();
        assertTrue(values.containsValue(41L), values.toString());
    }

    @Test
    @DisplayName("没有授权店铺的货件时不查明细表，返回空页而不是跳过校验")
    void skipsItemQueryWhenNoShipments() {
        when(fbaShipmentMapper.selectList(any())).thenReturn(Collections.emptyList());

        PageResult<ReceiptShortage> page = service.listReceiptShortages(1L, PageRequest.first(50));

        assertEquals(0, page.items().size());
        verify(fbaShipmentItemMapper, never()).selectList(any());
    }

    @Test
    @DisplayName("越权店铺直接拒绝：不能退化成空列表，那会让「没权限」看起来像「没有短收」")
    void rejectsForeignShop() {
        CodeErrorException ex = assertThrows(CodeErrorException.class,
                () -> service.listReceiptShortages(999L, PageRequest.first(50)));
        assertTrue(ex.getMessage().contains("无权查询该店铺的入库短收"), ex.getMessage());
        verify(fbaShipmentMapper, never()).selectList(any());
    }

    @Test
    @DisplayName("明细缺签收数或应发数时不产生半成品行（shortUnits 不能是 null 减法）")
    void dropsRowsWithMissingQuantities() {
        when(fbaShipmentMapper.selectList(any())).thenReturn(List.of(shipment(31L, "FBA1")));
        FbaShipmentItem broken = item(43L, 31L, "SKU-3", 10, 5);
        broken.setQuantity(null);
        when(fbaShipmentItemMapper.selectList(any())).thenReturn(List.of(broken));

        PageResult<ReceiptShortage> page = service.listReceiptShortages(1L, PageRequest.first(50));

        assertEquals(0, page.items().size());
        assertFalse(page.items().stream().anyMatch(r -> r.getShortUnits() == null), "不应出现短收件数为 null 的行");
    }
}
