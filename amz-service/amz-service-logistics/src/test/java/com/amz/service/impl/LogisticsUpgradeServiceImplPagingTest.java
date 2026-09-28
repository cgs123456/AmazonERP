package com.amz.service.impl;

import com.amz.context.UserContext;
import com.amz.exception.CodeErrorException;
import com.amz.mapper.CarrierQuoteMapper;
import com.amz.mapper.FbaReceiptDiscrepancyMapper;
import com.amz.mapper.FreightAllocationMapper;
import com.amz.mapper.InventoryTransferMapper;
import com.amz.mapper.ShipmentMapper;
import com.amz.model.CarrierQuote;
import com.amz.model.FbaReceiptDiscrepancy;
import com.amz.model.FreightAllocation;
import com.amz.model.InventoryTransfer;
import com.amz.model.Shipment;
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

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("物流升级列表游标分页测试")
class LogisticsUpgradeServiceImplPagingTest {

    @Mock
    private CarrierQuoteMapper carrierQuoteMapper;

    @Mock
    private InventoryTransferMapper inventoryTransferMapper;

    @Mock
    private FreightAllocationMapper freightAllocationMapper;

    @Mock
    private FbaReceiptDiscrepancyMapper fbaReceiptDiscrepancyMapper;

    @Mock
    private ShipmentMapper shipmentMapper;

    @InjectMocks
    private LogisticsUpgradeServiceImpl service;

    @BeforeAll
    static void initMybatisTableInfo() {
        for (Class<?> model : List.of(
                CarrierQuote.class, InventoryTransfer.class, FreightAllocation.class,
                FbaReceiptDiscrepancy.class, Shipment.class)) {
            TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), model);
        }
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
    @DisplayName("报价列表：探测行丢弃、有效期与 status 过滤保留、id 游标有界")
    @SuppressWarnings({"unchecked", "rawtypes"})
    void listQuotesUsesStableBoundedKeyset() {
        when(carrierQuoteMapper.selectList(any())).thenReturn(List.of(
                quote(40L), quote(39L), quote(38L)));

        PageResult<CarrierQuote> page = service.listQuotes(
                1L, "SEA", PageRequest.of(2, PageRequest.encodeCursor(900L)));

        assertEquals(List.of(40L, 39L), page.items().stream().map(CarrierQuote::getId).toList());
        assertTrue(page.truncated());
        assertEquals(PageRequest.encodeCursor(39L), page.nextCursor());

        ArgumentCaptor<LambdaQueryWrapper<CarrierQuote>> captor =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(carrierQuoteMapper).selectList(captor.capture());
        LambdaQueryWrapper<CarrierQuote> wrapper = captor.getValue();
        assertTrue(wrapper.getCustomSqlSegment().contains("LIMIT"));
        assertTrue(wrapper.getCustomSqlSegment().contains("expiry_date"));
        assertTrue(wrapper.getParamNameValuePairs().containsValue(900L));
        assertTrue(wrapper.getParamNameValuePairs().containsValue("ACTIVE"));
        assertTrue(wrapper.getParamNameValuePairs().containsValue("SEA"));
    }

    @Test
    @DisplayName("调拨列表：status 与 id 游标同时生效，并拒绝他店")
    @SuppressWarnings({"unchecked", "rawtypes"})
    void listTransfersUsesBoundedKeysetAndChecksShop() {
        when(inventoryTransferMapper.selectList(any())).thenReturn(List.of(
                transfer(30L), transfer(29L), transfer(28L)));

        PageResult<InventoryTransfer> page = service.listTransfers(
                1L, "IN_TRANSIT", PageRequest.of(2, PageRequest.encodeCursor(800L)));

        assertEquals(List.of(30L, 29L), page.items().stream().map(InventoryTransfer::getId).toList());
        assertTrue(page.truncated());
        assertEquals(PageRequest.encodeCursor(29L), page.nextCursor());

        ArgumentCaptor<LambdaQueryWrapper<InventoryTransfer>> captor =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(inventoryTransferMapper).selectList(captor.capture());
        LambdaQueryWrapper<InventoryTransfer> wrapper = captor.getValue();
        assertTrue(wrapper.getCustomSqlSegment().contains("LIMIT"));
        assertTrue(wrapper.getParamNameValuePairs().containsValue(800L));
        assertTrue(wrapper.getParamNameValuePairs().containsValue("IN_TRANSIT"));

        assertThrows(CodeErrorException.class, () -> service.listTransfers(2L, null, PageRequest.first(50)));
    }

    @Test
    @DisplayName("头程分摊列表：先校验货件归属，再按 shipmentId + id 游标有界查询")
    @SuppressWarnings({"unchecked", "rawtypes"})
    void listAllocationsChecksShipmentThenUsesBoundedKeyset() {
        Shipment shipment = new Shipment();
        shipment.setId(41L);
        shipment.setShopId(1L);
        when(shipmentMapper.selectById(41L)).thenReturn(shipment);
        when(freightAllocationMapper.selectList(any())).thenReturn(List.of(
                allocation(70L), allocation(69L), allocation(68L)));

        PageResult<FreightAllocation> page = service.listAllocations(
                41L, PageRequest.of(2, PageRequest.encodeCursor(700L)));

        assertEquals(List.of(70L, 69L), page.items().stream().map(FreightAllocation::getId).toList());
        assertTrue(page.truncated());
        assertEquals(PageRequest.encodeCursor(69L), page.nextCursor());

        ArgumentCaptor<LambdaQueryWrapper<FreightAllocation>> captor =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(freightAllocationMapper).selectList(captor.capture());
        LambdaQueryWrapper<FreightAllocation> wrapper = captor.getValue();
        assertTrue(wrapper.getCustomSqlSegment().contains("LIMIT"));
        assertTrue(wrapper.getParamNameValuePairs().containsValue(700L));
        assertTrue(wrapper.getParamNameValuePairs().containsValue(41L));

        when(shipmentMapper.selectById(99L)).thenReturn(null);
        assertThrows(CodeErrorException.class,
                () -> service.listAllocations(99L, PageRequest.first(50)));
    }

    @Test
    @DisplayName("签收差异列表：status 与 id 游标同时生效，并拒绝他店")
    @SuppressWarnings({"unchecked", "rawtypes"})
    void listDiscrepanciesUsesBoundedKeysetAndChecksShop() {
        when(fbaReceiptDiscrepancyMapper.selectList(any())).thenReturn(List.of(
                discrepancy(50L), discrepancy(49L), discrepancy(48L)));

        PageResult<FbaReceiptDiscrepancy> page = service.listDiscrepancies(
                1L, "PENDING", PageRequest.of(2, PageRequest.encodeCursor(600L)));

        assertEquals(List.of(50L, 49L), page.items().stream().map(FbaReceiptDiscrepancy::getId).toList());
        assertTrue(page.truncated());
        assertEquals(PageRequest.encodeCursor(49L), page.nextCursor());

        ArgumentCaptor<LambdaQueryWrapper<FbaReceiptDiscrepancy>> captor =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(fbaReceiptDiscrepancyMapper).selectList(captor.capture());
        LambdaQueryWrapper<FbaReceiptDiscrepancy> wrapper = captor.getValue();
        assertTrue(wrapper.getCustomSqlSegment().contains("LIMIT"));
        assertTrue(wrapper.getParamNameValuePairs().containsValue(600L));
        assertTrue(wrapper.getParamNameValuePairs().containsValue("PENDING"));

        clearInvocations(fbaReceiptDiscrepancyMapper);
        assertThrows(CodeErrorException.class,
                () -> service.listDiscrepancies(2L, null, PageRequest.first(50)));
        verify(fbaReceiptDiscrepancyMapper, never()).selectList(any());
    }

    private static CarrierQuote quote(Long id) {
        CarrierQuote quote = new CarrierQuote();
        quote.setId(id);
        quote.setShopId(1L);
        quote.setStatus("ACTIVE");
        return quote;
    }

    private static InventoryTransfer transfer(Long id) {
        InventoryTransfer transfer = new InventoryTransfer();
        transfer.setId(id);
        transfer.setShopId(1L);
        transfer.setStatus("IN_TRANSIT");
        return transfer;
    }

    private static FreightAllocation allocation(Long id) {
        FreightAllocation allocation = new FreightAllocation();
        allocation.setId(id);
        allocation.setShipmentId(41L);
        return allocation;
    }

    private static FbaReceiptDiscrepancy discrepancy(Long id) {
        FbaReceiptDiscrepancy discrepancy = new FbaReceiptDiscrepancy();
        discrepancy.setId(id);
        discrepancy.setShopId(1L);
        discrepancy.setStatus("PENDING");
        return discrepancy;
    }
}