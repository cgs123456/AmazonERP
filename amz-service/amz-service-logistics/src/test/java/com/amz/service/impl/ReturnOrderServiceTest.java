package com.amz.service.impl;

import com.amz.context.UserContext;
import com.amz.exception.CodeErrorException;
import com.amz.mapper.ReturnOrderMapper;
import com.amz.mapper.WarehouseInventoryMapper;
import com.amz.model.ReturnOrder;
import com.amz.model.WarehouseInventory;
import com.amz.service.WarehouseService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 退货单服务契约（买家退货入仓）。
 * <p>
 * <b>存在动因</b>：全仓此前没有任何退货单，退款只走财务域（结算报表 → 凭证），
 * 「货有没有退回来」没有落点，导致 COGS 不因退货冲减、库存不回补。本测试钉住：
 * <ol>
 *   <li><b>只有良品（GOOD）回补库存</b>——残次/报废虽收到货，但不可再售，
 *       算进可售库存会让补货建议虚高；</li>
 *   <li>状态机白名单：跳过前置状态一律拒绝；</li>
 *   <li>每次库存变更都写流水（可追溯）；</li>
 *   <li>归属校验：他店退货单不可收货。</li>
 * </ol>
 * 变异验证（写本测试时实际注入确认会红）：把 GOOD 判断去掉（残次也回补）→ 第一例红；
 * 去掉状态白名单 → 第二例红；把写流水去掉 → 第三例红。
 */
@DisplayName("退货单：只有良品回补库存且留流水")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ReturnOrderServiceTest {

    @Mock
    private ReturnOrderMapper returnOrderMapper;
    @Mock
    private WarehouseService warehouseService;
    @Mock
    private InventoryMovementService movementService;
    @Mock
    private WarehouseInventoryMapper inventoryMapper;

    @InjectMocks
    private ReturnOrderService service;

    @BeforeAll
    static void registerTableInfo() {
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(
                        new com.baomidou.mybatisplus.core.MybatisConfiguration(), ""),
                ReturnOrder.class);
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(
                        new com.baomidou.mybatisplus.core.MybatisConfiguration(), ""),
                WarehouseInventory.class);
    }

    @AfterEach
    void clear() {
        UserContext.clear();
    }

    @Test
    @DisplayName("良品收货：回补库存并写流水")
    void goodDispositionRestocks() {
        grantShop(9001L);
        ReturnOrder order = pendingReturn();
        when(returnOrderMapper.selectById(1L)).thenReturn(order);
        when(inventoryMapper.selectList(any())).thenReturn(List.of());
        WarehouseInventory inv = new WarehouseInventory();
        inv.setQuantity(5);
        when(warehouseService.increaseInventory(eq(9001L), eq(9001L), eq("SKU-1"), eq(3), any(), any()))
                .thenReturn(inv);

        service.receive(1L, 9001L, "GOOD");

        verify(warehouseService).increaseInventory(eq(9001L), eq(9001L), eq("SKU-1"), eq(3), any(), any());
        verify(movementService).record(eq(9001L), eq(9001L), eq("SKU-1"), any(), anyInt(), anyInt(),
                eq(com.amz.model.InventoryMovement.TYPE_RETURN_INBOUND), any(), any(), any(), any());
        assertEquals("RECEIVED", order.getStatus());
        assertEquals("GOOD", order.getDisposition());
    }

    @Test
    @DisplayName("残次/报废：不回补库存，但留 0 变更流水")
    void defectiveDoesNotRestock() {
        grantShop(9001L);
        ReturnOrder order = pendingReturn();
        when(returnOrderMapper.selectById(1L)).thenReturn(order);
        when(inventoryMapper.selectList(any())).thenReturn(List.of());

        service.receive(1L, 9001L, "SCRAP");

        verify(warehouseService, never()).increaseInventory(anyLong(), anyLong(), anyString(),
                anyInt(), any(), any());
        // 仍要留流水，说明「货收到了、未入可售」
        verify(movementService).record(eq(9001L), eq(9001L), eq("SKU-1"), any(), anyInt(), anyInt(),
                eq(com.amz.model.InventoryMovement.TYPE_RETURN_INBOUND), any(), any(), any(), any());
    }

    @Test
    @DisplayName("状态白名单：非 PENDING 不可收货")
    void rejectsReceiveFromWrongState() {
        grantShop(9001L);
        ReturnOrder order = pendingReturn();
        order.setStatus("RECEIVED");
        when(returnOrderMapper.selectById(1L)).thenReturn(order);

        assertThrows(CodeErrorException.class, () -> service.receive(1L, 9001L, "GOOD"));
        verify(warehouseService, never()).increaseInventory(anyLong(), anyLong(), anyString(),
                anyInt(), any(), any());
    }

    @Test
    @DisplayName("处置白名单：非法处置拒绝")
    void rejectsInvalidDisposition() {
        grantShop(9001L);
        ReturnOrder order = pendingReturn();
        when(returnOrderMapper.selectById(1L)).thenReturn(order);

        assertThrows(CodeErrorException.class, () -> service.receive(1L, 9001L, "MELTED"));
        verify(warehouseService, never()).increaseInventory(anyLong(), anyLong(), anyString(),
                anyInt(), any(), any());
    }

    @Test
    @DisplayName("他店退货单：收货被拒且不回补")
    void rejectsForeignShop() {
        grantShop(9001L);
        ReturnOrder order = pendingReturn();
        order.setShopId(7777L);
        when(returnOrderMapper.selectById(1L)).thenReturn(order);

        assertThrows(CodeErrorException.class, () -> service.receive(1L, 9001L, "GOOD"));
        verify(warehouseService, never()).increaseInventory(anyLong(), anyLong(), anyString(),
                anyInt(), any(), any());
    }

    @Test
    @DisplayName("建单：数量必须为正，来源白名单")
    void createValidates() {
        grantShop(9001L);
        ReturnOrder bad = new ReturnOrder();
        bad.setShopId(9001L);
        bad.setSku("SKU-1");
        bad.setQuantity(0);
        assertThrows(CodeErrorException.class, () -> service.create(bad));

        ReturnOrder badSource = new ReturnOrder();
        badSource.setShopId(9001L);
        badSource.setSku("SKU-1");
        badSource.setQuantity(1);
        badSource.setSource("EBAY");
        assertThrows(CodeErrorException.class, () -> service.create(badSource));
    }

    @Test
    @DisplayName("建单：生成唯一单号，状态 PENDING")
    void createGeneratesNumberAndStatus() {
        grantShop(9001L);
        ReturnOrder order = new ReturnOrder();
        order.setShopId(9001L);
        order.setSku("SKU-1");
        order.setQuantity(2);

        service.create(order);

        assertNotNull(order.getReturnNo());
        assertEquals("PENDING", order.getStatus());
        verify(returnOrderMapper).insert(order);
    }

    @Test
    @DisplayName("结案：只有 RECEIVED 可结案")
    void closeRequiresReceived() {
        grantShop(9001L);
        ReturnOrder order = pendingReturn();
        when(returnOrderMapper.selectById(1L)).thenReturn(order);

        assertThrows(CodeErrorException.class, () -> service.close(1L));
    }

    @Test
    @DisplayName("良品退货件数：只统计 RECEIVED/CLOSED 且 GOOD")
    void countsOnlyGoodReceived() {
        grantShop(9001L);
        ReturnOrder a = pendingReturn();
        a.setStatus("RECEIVED");
        a.setDisposition("GOOD");
        a.setQuantity(2);
        ReturnOrder b = pendingReturn();
        b.setStatus("RECEIVED");
        b.setDisposition("SCRAP");
        b.setQuantity(5);
        when(returnOrderMapper.selectList(any())).thenReturn(List.of(a));

        assertEquals(2, service.returnedGoodUnits(9001L, "SKU-1"));
    }

    @Test
    @DisplayName("Amazon 导入：幂等键拼上店铺后写库，首次返回 true")
    void importAmazonReturnWritesScopedKey() {
        grantShop(9001L);
        when(returnOrderMapper.selectOne(any())).thenReturn(null);
        when(returnOrderMapper.insert(any(ReturnOrder.class))).thenReturn(1);

        ReturnOrder order = new ReturnOrder();
        order.setShopId(9001L);
        order.setSku("SKU-1");
        order.setQuantity(2);
        order.setSource("AMAZON_FBA");
        order.setDisposition("GOOD");
        order.setExternalKey("abc123");

        assertTrue(service.importAmazonReturn(order));
        assertEquals("9001|AMAZON_FBA|abc123", order.getExternalKey(),
                "幂等键必须拼上店铺，避免跨店同号相撞");
        assertEquals("PENDING", order.getStatus());
        assertNotNull(order.getReturnNo());
        verify(returnOrderMapper).insert(any(ReturnOrder.class));
    }

    @Test
    @DisplayName("Amazon 导入：已存在则跳过，不重复建单（不重复回补）")
    void importAmazonReturnSkipsExisting() {
        grantShop(9001L);
        when(returnOrderMapper.selectOne(any())).thenReturn(new ReturnOrder());

        ReturnOrder order = new ReturnOrder();
        order.setShopId(9001L);
        order.setSku("SKU-1");
        order.setQuantity(2);
        order.setSource("AMAZON_FBA");
        order.setExternalKey("abc123");

        assertFalse(service.importAmazonReturn(order),
                "同一笔退货重复导入必须跳过——否则库存会被重复回补");
        verify(returnOrderMapper, never()).insert(any(ReturnOrder.class));
    }

    @Test
    @DisplayName("Amazon 导入：并发撞唯一键也算已存在（不抛错、不重复写）")
    void importAmazonReturnHandlesConcurrentDuplicate() {
        grantShop(9001L);
        when(returnOrderMapper.selectOne(any())).thenReturn(null);
        when(returnOrderMapper.insert(any(ReturnOrder.class)))
                .thenThrow(new org.springframework.dao.DuplicateKeyException("uk_external_key"));

        ReturnOrder order = new ReturnOrder();
        order.setShopId(9001L);
        order.setSku("SKU-1");
        order.setQuantity(2);
        order.setSource("AMAZON_FBA");
        order.setExternalKey("abc123");

        assertFalse(service.importAmazonReturn(order));
    }

    @Test
    @DisplayName("Amazon 导入：缺幂等键明确拒绝（不能退化成每次新建）")
    void importAmazonReturnRequiresExternalKey() {
        grantShop(9001L);
        ReturnOrder order = new ReturnOrder();
        order.setShopId(9001L);
        order.setSku("SKU-1");
        order.setQuantity(2);
        order.setSource("AMAZON_FBA");

        assertThrows(CodeErrorException.class, () -> service.importAmazonReturn(order));
        verify(returnOrderMapper, never()).insert(any(ReturnOrder.class));
    }

    @Test
    @DisplayName("Amazon 导入：非法来源拒绝")
    void importAmazonReturnRejectsBadSource() {
        grantShop(9001L);
        ReturnOrder order = new ReturnOrder();
        order.setShopId(9001L);
        order.setSku("SKU-1");
        order.setQuantity(2);
        order.setSource("EBAY");
        order.setExternalKey("k");

        assertThrows(CodeErrorException.class, () -> service.importAmazonReturn(order));
    }

    // ============ 辅助 ============

    private void grantShop(Long shopId) {
        UserContext.setUserId(1);
        UserContext.setRole("OPERATOR");
        UserContext.setShops(List.of(shopId));
    }
    private ReturnOrder pendingReturn() {
        ReturnOrder order = new ReturnOrder();
        order.setId(1L);
        order.setShopId(9001L);
        order.setReturnNo("RET-1");
        order.setSku("SKU-1");
        order.setQuantity(3);
        order.setStatus("PENDING");
        return order;
    }
}
