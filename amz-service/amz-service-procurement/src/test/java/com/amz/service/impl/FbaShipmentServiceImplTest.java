package com.amz.service.impl;

import com.amz.context.UserContext;
import com.amz.exception.AttrIsNullException;
import com.amz.exception.CodeErrorException;
import com.amz.mapper.FbaShipmentItemMapper;
import com.amz.mapper.FbaShipmentMapper;
import com.amz.mapper.InventoryBatchMapper;
import com.amz.model.FbaShipment;
import com.amz.model.FbaShipmentItem;
import com.amz.model.InventoryBatch;
import org.springframework.dao.DuplicateKeyException;
import com.amz.result.PageRequest;
import com.amz.result.PageResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * FBA 货件服务多租户与库存一致性测试（纯 Mockito，不依赖数据库）。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("FBA 货件多租户与库存一致性测试")
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

    private static FbaShipmentItem item(long id) {
        FbaShipmentItem item = new FbaShipmentItem();
        item.setId(id);
        item.setFbaShipmentId(7L);
        item.setSku("SKU-" + id);
        item.setQuantity(1);
        item.setUnitCost(new BigDecimal("10.00"));
        return item;
    }

    private static InventoryBatch batch(long id, int available) {
        InventoryBatch b = new InventoryBatch();
        b.setId(id);
        b.setShopId(1L);
        b.setSku("SKU-1");
        b.setBatchNo("BAT-" + id);
        b.setQuantity(available);
        b.setAvailableQuantity(available);
        b.setUnitCost(new BigDecimal("10.00"));
        b.setInboundDate(LocalDate.now());
        b.setStatus("ACTIVE");
        return b;
    }

    @Test
    @DisplayName("getShipment：非授权店铺应返回业务失败")
    void getShipmentDeniedForOtherShop() {
        UserContext.setShops(List.of(1L));
        when(fbaShipmentMapper.selectById(7L)).thenReturn(shipment(99L));

        assertThrows(CodeErrorException.class, () -> shipmentService.getShipment(7L));
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
    @DisplayName("createShipment：非授权店铺拒绝且不写库")
    void createShipmentDeniedForOtherShop() {
        UserContext.setShops(List.of(1L));

        assertThrows(CodeErrorException.class, () -> shipmentService.createShipment(shipment(99L)));
        verify(fbaShipmentMapper, never()).insert(any(FbaShipment.class));
    }

    @Test
    @DisplayName("listShipments：非授权店铺拒绝且不查询")
    void listShipmentsDeniedForOtherShop() {
        UserContext.setShops(List.of(1L));

        assertThrows(CodeErrorException.class, () -> shipmentService.listShipments(99L, null, PageRequest.first(50)));
        verify(fbaShipmentMapper, never()).selectList(any());
    }

    @Test
    @DisplayName("updateShipment：请求体 shopId 不得搬移货件归属")
    void updateShipmentLocksShopId() {
        UserContext.setShops(List.of(1L));
        when(fbaShipmentMapper.selectById(7L)).thenReturn(shipment(1L));

        FbaShipment input = shipment(99L);
        shipmentService.updateShipment(input);

        assertEquals(1L, input.getShopId());
    }

    @Test
    @DisplayName("processReceipt：空明细/缺字段/负数直接参数异常，而非 NPE 穿透 500")
    void processReceiptValidatesItems() {
        UserContext.setShops(List.of(1L));
        when(fbaShipmentMapper.selectById(7L)).thenReturn(shipment(1L));
        when(fbaShipmentItemMapper.selectList(any())).thenReturn(List.of());

        assertThrows(AttrIsNullException.class,
                () -> shipmentService.processReceipt(7L, null));
        assertThrows(AttrIsNullException.class,
                () -> shipmentService.processReceipt(7L, List.of()));
        assertThrows(AttrIsNullException.class,
                () -> shipmentService.processReceipt(7L, List.of(Map.of("itemId", 1))));

        Map<String, Object> negative = new java.util.HashMap<>();
        negative.put("itemId", 1);
        negative.put("receivedQty", -5);
        assertThrows(AttrIsNullException.class,
                () -> shipmentService.processReceipt(7L, List.of(negative)));
    }

    @Test
    @DisplayName("processReceipt：仅提交部分明细时不得把整单关闭")
    void processReceiptDoesNotCloseShipmentWhenItemsRemain() {
        UserContext.setShops(List.of(1L));
        FbaShipment shipment = shipment(1L);
        when(fbaShipmentMapper.selectById(7L)).thenReturn(shipment);

        FbaShipmentItem receivedItem = item(1L);
        receivedItem.setQuantity(2);
        FbaShipmentItem pendingItem = item(2L);
        pendingItem.setQuantity(3);
        when(fbaShipmentItemMapper.selectList(any())).thenReturn(List.of(receivedItem, pendingItem));
        when(fbaShipmentItemMapper.selectById(1L)).thenReturn(receivedItem);
        when(fbaShipmentItemMapper.updateById(any(FbaShipmentItem.class))).thenReturn(1);
        when(inventoryBatchMapper.insert(any(InventoryBatch.class))).thenReturn(1);

        Map<String, Object> receipt = new java.util.HashMap<>();
        receipt.put("itemId", 1L);
        receipt.put("receivedQty", 2);

        Map<String, Object> result = shipmentService.processReceipt(7L, List.of(receipt));

        assertEquals(false, result.get("allReceived"));
        assertEquals("RECEIVING", shipment.getStatus());
        assertNull(pendingItem.getReceivedQuantity());
        verify(inventoryBatchMapper, times(1)).insert(any(InventoryBatch.class));
    }

    @Test
    @DisplayName("processReceipt：同一请求重复 itemId 必须拒绝，禁止重复入库")
    void processReceiptRejectsDuplicateItemIds() {
        UserContext.setShops(List.of(1L));
        when(fbaShipmentMapper.selectById(7L)).thenReturn(shipment(1L));
        FbaShipmentItem shipmentItem = item(1L);
        shipmentItem.setQuantity(2);
        when(fbaShipmentItemMapper.selectList(any())).thenReturn(List.of(shipmentItem));

        Map<String, Object> first = new java.util.HashMap<>();
        first.put("itemId", 1L);
        first.put("receivedQty", 2);
        Map<String, Object> duplicate = new java.util.HashMap<>();
        duplicate.put("itemId", 1L);
        duplicate.put("receivedQty", 2);

        assertThrows(CodeErrorException.class,
                () -> shipmentService.processReceipt(7L, List.of(first, duplicate)));
        verify(fbaShipmentItemMapper, never()).updateById(any(FbaShipmentItem.class));
        verify(inventoryBatchMapper, never()).insert(any(InventoryBatch.class));
    }

    @Test
    @DisplayName("processReceipt：相同签收重试必须复用已有批次，不得重复建批次")
    void processReceiptRetryReusesExistingBatch() {
        UserContext.setShops(List.of(1L));
        FbaShipment shipment = shipment(1L);
        shipment.setShipmentNo("FBA-1");
        when(fbaShipmentMapper.selectById(7L)).thenReturn(shipment);

        FbaShipmentItem shipmentItem = item(1L);
        shipmentItem.setQuantity(2);
        when(fbaShipmentItemMapper.selectList(any())).thenReturn(List.of(shipmentItem));
        when(fbaShipmentItemMapper.selectById(1L)).thenReturn(shipmentItem);
        when(fbaShipmentItemMapper.updateById(any(FbaShipmentItem.class))).thenReturn(1);
        when(inventoryBatchMapper.insert(any(InventoryBatch.class))).thenReturn(1);
        when(inventoryBatchMapper.selectOne(any())).thenAnswer(invocation -> {
            InventoryBatch existing = new InventoryBatch();
            existing.setId(99L);
            existing.setBatchNo(shipmentItem.getBatchNo());
            existing.setShipmentItemId(1L);
            return existing;
        });

        Map<String, Object> receipt = new java.util.HashMap<>();
        receipt.put("itemId", 1L);
        receipt.put("receivedQty", 2);

        shipmentService.processReceipt(7L, List.of(receipt));
        shipmentService.processReceipt(7L, List.of(receipt));

        verify(inventoryBatchMapper, times(1)).insert(any(InventoryBatch.class));
    }

    @Test
    @DisplayName("receiveBatch：新批次必须绑定货件明细唯一键")
    void receiveBatchBindsShipmentItemId() {
        UserContext.setShops(List.of(1L));
        when(fbaShipmentMapper.selectById(7L)).thenReturn(shipment(1L));
        FbaShipmentItem shipmentItem = item(1L);
        shipmentItem.setQuantity(2);
        when(fbaShipmentItemMapper.selectById(1L)).thenReturn(shipmentItem);
        when(fbaShipmentItemMapper.updateById(any(FbaShipmentItem.class))).thenReturn(1);
        when(inventoryBatchMapper.insert(any(InventoryBatch.class))).thenReturn(1);

        shipmentService.receiveBatch(7L, 1L, 2);

        org.mockito.ArgumentCaptor<InventoryBatch> captor =
                org.mockito.ArgumentCaptor.forClass(InventoryBatch.class);
        verify(inventoryBatchMapper).insert(captor.capture());
        assertEquals(1L, captor.getValue().getShipmentItemId());
    }

    @Test
    @DisplayName("receiveBatch：并发唯一键冲突时回查并绑定已有批次")
    void receiveBatchRecoversFromConcurrentInsertConflict() {
        UserContext.setShops(List.of(1L));
        when(fbaShipmentMapper.selectById(7L)).thenReturn(shipment(1L));
        FbaShipmentItem shipmentItem = item(1L);
        shipmentItem.setQuantity(2);
        when(fbaShipmentItemMapper.selectById(1L)).thenReturn(shipmentItem);
        when(inventoryBatchMapper.insert(any(InventoryBatch.class)))
                .thenThrow(new DuplicateKeyException("duplicate shipment item batch"));

        InventoryBatch existing = new InventoryBatch();
        existing.setId(99L);
        existing.setBatchNo("BAT-EXISTING");
        existing.setShipmentItemId(1L);
        when(inventoryBatchMapper.selectOne(any())).thenReturn(existing);
        when(fbaShipmentItemMapper.updateById(any(FbaShipmentItem.class))).thenReturn(1);

        InventoryBatch result = shipmentService.receiveBatch(7L, 1L, 2);

        assertEquals(99L, result.getId());
        assertEquals("BAT-EXISTING", shipmentItem.getBatchNo());
        verify(inventoryBatchMapper).insert(any(InventoryBatch.class));
        verify(fbaShipmentItemMapper).updateById(shipmentItem);
    }

    @Test
    @DisplayName("货件明细：公开接口使用游标分页，探测行不返回")
    void listShipmentItemsUsesCursorPagination() {
        UserContext.setShops(List.of(1L));
        when(fbaShipmentMapper.selectById(7L)).thenReturn(shipment(1L));
        when(fbaShipmentItemMapper.selectList(any())).thenReturn(List.of(
                item(30L), item(29L), item(28L)));

        PageResult<FbaShipmentItem> page =
                shipmentService.listShipmentItems(7L, PageRequest.first(2));

        assertEquals(List.of(30L, 29L), page.items().stream().map(FbaShipmentItem::getId).toList());
        assertTrue(page.truncated());
        assertEquals(PageRequest.encodeCursor(29L), page.nextCursor());
    }

    @Test
    @DisplayName("费用分摊：内部调用必须跨页取全量，不得只处理第一页")
    void allocateCostsLoadsAllPages() {
        UserContext.setShops(List.of(1L));
        FbaShipment shipment = shipment(1L);
        shipment.setFreightCost(new BigDecimal("501.00"));
        when(fbaShipmentMapper.selectById(7L)).thenReturn(shipment);

        List<FbaShipmentItem> firstPage = new java.util.ArrayList<>();
        for (long id = 1000L; id >= 500L; id--) {
            firstPage.add(item(id));
        }
        when(fbaShipmentItemMapper.selectList(any()))
                .thenReturn(firstPage, List.of(item(499L)));

        Map<String, Object> result = shipmentService.allocateCosts(7L);

        assertEquals(501, result.get("totalQuantity"));
        assertEquals(501, ((List<?>) result.get("allocationDetails")).size());
        verify(fbaShipmentItemMapper, times(501)).updateById(any(FbaShipmentItem.class));
    }

    @Test
    @DisplayName("费用分摊：明细超过硬上限时整单失败，禁止部分写入")
    void allocateCostsFailsClosedAboveHardLimit() {
        UserContext.setShops(List.of(1L));
        when(fbaShipmentMapper.selectById(7L)).thenReturn(shipment(1L));

        List<FbaShipmentItem> oversized = new java.util.ArrayList<>();
        for (long id = 20000L; id > 10000L; id--) {
            oversized.add(item(id));
        }
        when(fbaShipmentItemMapper.selectList(any())).thenReturn(oversized);

        assertThrows(CodeErrorException.class, () -> shipmentService.allocateCosts(7L));
        verify(fbaShipmentItemMapper, never()).updateById(any(FbaShipmentItem.class));
    }

    @Test
    @DisplayName("listBatchesBySku：非授权店铺拒绝且不查询")
    void listBatchesDeniedForOtherShop() {
        UserContext.setShops(List.of(1L));

        assertThrows(CodeErrorException.class,
                () -> shipmentService.listBatchesBySku(99L, "SKU-1", PageRequest.first(1)));
        verify(inventoryBatchMapper, never()).selectList(any());
    }

    @Test
    @DisplayName("fifoOutbound：数量为空或非正数拒绝，不触碰库存")
    void fifoOutboundRejectsInvalidQuantity() {
        UserContext.setShops(List.of(1L));

        assertThrows(AttrIsNullException.class,
                () -> shipmentService.fifoOutbound(1L, "SKU-1", null));
        assertThrows(CodeErrorException.class,
                () -> shipmentService.fifoOutbound(1L, "SKU-1", 0));
        assertThrows(CodeErrorException.class,
                () -> shipmentService.fifoOutbound(1L, "SKU-1", -1));
        verify(inventoryBatchMapper, never()).selectList(any());
    }

    @Test
    @DisplayName("fifoOutbound：库存不足整体失败，禁止部分扣减")
    void fifoOutboundRejectsInsufficientStockAtomically() {
        UserContext.setShops(List.of(1L));
        when(inventoryBatchMapper.selectList(any())).thenReturn(List.of(batch(1L, 3)));

        assertThrows(CodeErrorException.class,
                () -> shipmentService.fifoOutbound(1L, "SKU-1", 5));
        verify(inventoryBatchMapper, never()).updateById(any(InventoryBatch.class));
    }

    @Test
    @DisplayName("fifoOutbound：正常出库使用原子扣减，不整行覆盖")
    void fifoOutboundUsesAtomicDeduction() {
        UserContext.setShops(List.of(1L));
        when(inventoryBatchMapper.selectList(any())).thenReturn(List.of(batch(1L, 5)));
        when(inventoryBatchMapper.decreaseAvailableQuantityAtomic(1L, 1L, "SKU-1", 3)).thenReturn(1);

        List<Map<String, Object>> details = shipmentService.fifoOutbound(1L, "SKU-1", 3);

        assertEquals(1, details.size());
        assertEquals(3, details.get(0).get("quantity"));
        verify(inventoryBatchMapper).decreaseAvailableQuantityAtomic(1L, 1L, "SKU-1", 3);
        verify(inventoryBatchMapper, never()).updateById(any(InventoryBatch.class));
    }

    @Test
    @DisplayName("fifoOutbound：原子扣减受影响行数为 0 时整体失败，不回退整行覆盖")
    void fifoOutboundRejectsAtomicDeductionFailure() {
        UserContext.setShops(List.of(1L));
        when(inventoryBatchMapper.selectList(any())).thenReturn(List.of(batch(1L, 5)));
        when(inventoryBatchMapper.decreaseAvailableQuantityAtomic(1L, 1L, "SKU-1", 5)).thenReturn(0);

        assertThrows(CodeErrorException.class,
                () -> shipmentService.fifoOutbound(1L, "SKU-1", 5));
        verify(inventoryBatchMapper).decreaseAvailableQuantityAtomic(1L, 1L, "SKU-1", 5);
        verify(inventoryBatchMapper, never()).updateById(any(InventoryBatch.class));
    }
}
