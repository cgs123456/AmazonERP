package com.amz.service.impl;

import com.amz.context.UserContext;
import com.amz.exception.CodeErrorException;
import com.amz.mapper.InventoryBatchMapper;
import com.amz.model.InventoryBatch;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * FIFO 回补契约（与 fifoOutbound 对称）。
 * <p>
 * <b>存在动因</b>：fifoOutbound 此前只有出库、没有对称回补——退货回来了批次可用量却不恢复，
 * 售出成本因此永远虚高。本测试钉住：
 * <ol>
 *   <li>给了原批次号 → 冲回原批次（成本口径最准）；</li>
 *   <li>无原批次 → 按最新批次回补；</li>
 *   <li>回补走「先激活再加量」两条语句（DEPLETED 批次要能复活）；</li>
 *   <li>批次不存在 → 明确失败，不静默丢件。</li>
 * </ol>
 * 变异验证（写本测试时实际注入确认会红）：把 reactivateBatch 调用去掉 → 第一例红；
 * 把「无批次即失败」改成静默返回 → 第四例红。
 */
@DisplayName("FIFO 回补：与出库对称且不丢件")
class FbaShipmentFifoReturnTest {

    @AfterEach
    void cleanup() {
        UserContext.clear();
        UserContext.setShops(List.of(9001L));
    }

    @Test
    @DisplayName("给定原批次号：冲回原批次，先激活再加量")
    void returnsToOriginalBatch() {
        InventoryBatchMapper mapper = mock(InventoryBatchMapper.class);
        FbaShipmentServiceImpl service = serviceWith(mapper);
        InventoryBatch batch = batch(11L, "B-OLD");
        when(mapper.selectList(any())).thenReturn(List.of(batch));
        when(mapper.reactivateBatch(anyLong(), anyLong(), anyString())).thenReturn(1);
        when(mapper.increaseAvailableQuantityAtomic(anyLong(), anyLong(), anyString(), anyInt()))
                .thenReturn(1);

        List<Map<String, Object>> details = service.fifoReturn(9001L, "SKU-1", 3, "B-OLD");

        verify(mapper).reactivateBatch(eq(11L), eq(9001L), eq("SKU-1"));
        verify(mapper).increaseAvailableQuantityAtomic(eq(11L), eq(9001L), eq("SKU-1"), eq(3));
        assertEquals(1, details.size());
        assertEquals("B-OLD", details.get(0).get("batchNo"));
        assertEquals(3, details.get(0).get("quantity"));
    }

    @Test
    @DisplayName("无原批次：按最新批次回补")
    void returnsToLatestWhenNoOriginal() {
        InventoryBatchMapper mapper = mock(InventoryBatchMapper.class);
        FbaShipmentServiceImpl service = serviceWith(mapper);
        when(mapper.selectList(any())).thenReturn(List.of(batch(22L, "B-NEW")));
        when(mapper.reactivateBatch(anyLong(), anyLong(), anyString())).thenReturn(1);
        when(mapper.increaseAvailableQuantityAtomic(anyLong(), anyLong(), anyString(), anyInt()))
                .thenReturn(1);

        List<Map<String, Object>> details = service.fifoReturn(9001L, "SKU-1", 2, null);

        verify(mapper).increaseAvailableQuantityAtomic(eq(22L), eq(9001L), eq("SKU-1"), eq(2));
        assertEquals("B-NEW", details.get(0).get("batchNo"));
    }

    @Test
    @DisplayName("批次归属校验失败：抛错回滚，不静默丢件")
    void failsWhenReactivateRejects() {
        InventoryBatchMapper mapper = mock(InventoryBatchMapper.class);
        FbaShipmentServiceImpl service = serviceWith(mapper);
        when(mapper.selectList(any())).thenReturn(List.of(batch(33L, "B-X")));
        when(mapper.reactivateBatch(anyLong(), anyLong(), anyString())).thenReturn(0);

        assertThrows(CodeErrorException.class, () -> service.fifoReturn(9001L, "SKU-1", 1, null));
        verify(mapper, never()).increaseAvailableQuantityAtomic(anyLong(), anyLong(), anyString(), anyInt());
    }

    @Test
    @DisplayName("无任何批次：明确失败，不静默返回空")
    void failsWhenNoBatchAtAll() {
        InventoryBatchMapper mapper = mock(InventoryBatchMapper.class);
        FbaShipmentServiceImpl service = serviceWith(mapper);
        when(mapper.selectList(any())).thenReturn(List.of());

        assertThrows(CodeErrorException.class, () -> service.fifoReturn(9001L, "SKU-1", 1, null));
    }

    @Test
    @DisplayName("回补数量必须为正")
    void rejectsNonPositiveQuantity() {
        InventoryBatchMapper mapper = mock(InventoryBatchMapper.class);
        FbaShipmentServiceImpl service = serviceWith(mapper);

        assertThrows(CodeErrorException.class, () -> service.fifoReturn(9001L, "SKU-1", 0, null));
        assertThrows(CodeErrorException.class, () -> service.fifoReturn(9001L, "SKU-1", -1, null));
        verify(mapper, never()).reactivateBatch(anyLong(), anyLong(), anyString());
    }

    // ============ 辅助 ============

    private FbaShipmentServiceImpl serviceWith(InventoryBatchMapper mapper) {
        FbaShipmentServiceImpl service = new FbaShipmentServiceImpl();
        ReflectionTestUtils.setField(service, "inventoryBatchMapper", mapper);
        return service;
    }

    private InventoryBatch batch(Long id, String batchNo) {
        InventoryBatch batch = new InventoryBatch();
        batch.setId(id);
        batch.setShopId(9001L);
        batch.setSku("SKU-1");
        batch.setBatchNo(batchNo);
        batch.setAvailableQuantity(0);
        batch.setUnitCost(new BigDecimal("10.00"));
        batch.setInboundDate(LocalDate.now());
        return batch;
    }
}
