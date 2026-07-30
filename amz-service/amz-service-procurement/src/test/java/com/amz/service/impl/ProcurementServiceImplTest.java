package com.amz.service.impl;

import com.amz.client.Alibaba1688Client;
import com.amz.exception.AttrIsNullException;
import com.amz.mapper.PurchaseOrderMapper;
import com.amz.mapper.QualityCheckMapper;
import com.amz.model.PurchaseOrder;
import com.amz.model.QualityCheck;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 采购供应链服务单元测试（纯 Mockito，不依赖数据库）。
 * <p>
 * 验证采购单创建（金额计算）、1688 提交（状态校验）、
 * 质检合格率判定（PASS/FAIL/CONDITIONAL 三档）等核心业务逻辑。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("采购供应链服务单元测试")
class ProcurementServiceImplTest {

    @Mock
    private PurchaseOrderMapper purchaseOrderMapper;

    @Mock
    private QualityCheckMapper qualityCheckMapper;

    @Mock
    private Alibaba1688Client alibaba1688Client;

    @InjectMocks
    private ProcurementServiceImpl procurementService;

    @Test
    @DisplayName("创建采购单 - 正常 → 计算总金额并设置 DRAFT 状态")
    void testCreatePurchaseOrderNormal() {
        PurchaseOrder order = new PurchaseOrder();
        order.setQuantity(100);
        order.setUnitPrice(new BigDecimal("12.50"));

        PurchaseOrder result = procurementService.createPurchaseOrder(order);

        assertNotNull(result.getOrderNo(), "应生成业务单号");
        assertEquals(new BigDecimal("1250.00"), result.getTotalAmount(),
                "总金额 = 12.50 × 100 = 1250.00");
        assertEquals("DRAFT", result.getStatus(), "初始状态应为 DRAFT");
        verify(purchaseOrderMapper).insert(order);
    }

    @Test
    @DisplayName("创建采购单 - 数量为空 → 抛出异常")
    void testCreatePurchaseOrderNullQuantity() {
        PurchaseOrder order = new PurchaseOrder();
        order.setUnitPrice(new BigDecimal("12.50"));

        assertThrows(AttrIsNullException.class,
                () -> procurementService.createPurchaseOrder(order));
        verify(purchaseOrderMapper, never()).insert(any(PurchaseOrder.class));
    }

    @Test
    @DisplayName("创建采购单 - 单价为空 → 抛出异常")
    void testCreatePurchaseOrderNullUnitPrice() {
        PurchaseOrder order = new PurchaseOrder();
        order.setQuantity(100);

        assertThrows(AttrIsNullException.class,
                () -> procurementService.createPurchaseOrder(order));
    }

    @Test
    @DisplayName("提交 1688 - 非 DRAFT 状态 → 抛出异常")
    void testSubmitTo1688WrongStatus() {
        PurchaseOrder order = new PurchaseOrder();
        order.setId(1L);
        order.setStatus("SUBMITTED");
        when(purchaseOrderMapper.selectById(1L)).thenReturn(order);

        assertThrows(IllegalStateException.class,
                () -> procurementService.submitTo1688(1L));
    }

    @Test
    @DisplayName("提交 1688 - DRAFT 状态 → 调用 1688 下单并更新状态")
    void testSubmitTo1688Normal() {
        PurchaseOrder order = new PurchaseOrder();
        order.setId(1L);
        order.setStatus("DRAFT");
        order.setSupplierOfferId("OFFER-001");
        order.setQuantity(100);
        order.setUnitPrice(new BigDecimal("12.50"));
        when(purchaseOrderMapper.selectById(1L)).thenReturn(order);
        when(alibaba1688Client.createOrder("OFFER-001", 100, new BigDecimal("12.50")))
                .thenReturn("ALI-ORDER-001");

        PurchaseOrder result = procurementService.submitTo1688(1L);

        assertEquals("ALI-ORDER-001", result.getAlibabaOrderNo());
        assertEquals("SUBMITTED", result.getStatus());
        verify(purchaseOrderMapper).updateById(order);
    }

    @Test
    @DisplayName("同步状态 - WAIT_RECEIVE → SHIPPED 并获取物流单号")
    void testSyncOrderStatusShipped() {
        PurchaseOrder order = new PurchaseOrder();
        order.setId(1L);
        order.setAlibabaOrderNo("ALI-001");
        when(purchaseOrderMapper.selectById(1L)).thenReturn(order);
        when(alibaba1688Client.queryOrderStatus("ALI-001")).thenReturn("WAIT_RECEIVE");
        when(alibaba1688Client.queryTrackingNo("ALI-001")).thenReturn("TRK-001");

        PurchaseOrder result = procurementService.syncOrderStatus(1L);

        assertEquals("SHIPPED", result.getStatus());
        assertEquals("TRK-001", result.getTrackingNo());
    }

    @Test
    @DisplayName("同步状态 - FINISHED → QC_PENDING")
    void testSyncOrderStatusQcPending() {
        PurchaseOrder order = new PurchaseOrder();
        order.setId(1L);
        order.setAlibabaOrderNo("ALI-001");
        when(purchaseOrderMapper.selectById(1L)).thenReturn(order);
        when(alibaba1688Client.queryOrderStatus("ALI-001")).thenReturn("FINISHED");

        PurchaseOrder result = procurementService.syncOrderStatus(1L);

        assertEquals("QC_PENDING", result.getStatus());
    }

    @Test
    @DisplayName("质检 - 合格率 97% ≥ 95% → PASS")
    void testSubmitQualityCheckPass() {
        PurchaseOrder order = new PurchaseOrder();
        order.setId(1L);
        order.setStatus("QC_PENDING");
        when(purchaseOrderMapper.selectById(1L)).thenReturn(order);

        QualityCheck result = procurementService.submitQualityCheck(1L, 100, 3, "轻微划痕", "质检员A");

        assertEquals(97, result.getPassedCount(), "100 - 3 = 97");
        assertEquals("PASS", result.getResult(), "97% ≥ 95% 应判定 PASS");
        assertEquals("QC_PASSED", order.getStatus());
        verify(qualityCheckMapper).insert(result);
    }

    @Test
    @DisplayName("质检 - 合格率 80% < 90% → FAIL")
    void testSubmitQualityCheckFail() {
        PurchaseOrder order = new PurchaseOrder();
        order.setId(1L);
        order.setStatus("QC_PENDING");
        when(purchaseOrderMapper.selectById(1L)).thenReturn(order);

        QualityCheck result = procurementService.submitQualityCheck(1L, 100, 20, "严重缺陷", "质检员B");

        assertEquals("FAIL", result.getResult(), "80% < 90% 应判定 FAIL");
        assertEquals("QC_FAILED", order.getStatus());
    }

    @Test
    @DisplayName("质检 - 合格率 92%（90%~95% 之间）→ CONDITIONAL")
    void testSubmitQualityCheckConditional() {
        PurchaseOrder order = new PurchaseOrder();
        order.setId(1L);
        order.setStatus("QC_PENDING");
        when(purchaseOrderMapper.selectById(1L)).thenReturn(order);

        QualityCheck result = procurementService.submitQualityCheck(1L, 100, 8, "少量瑕疵", "质检员C");

        assertEquals("CONDITIONAL", result.getResult(), "92% 在 90%~95% 之间应判定 CONDITIONAL");
        assertEquals("QC_PASSED", order.getStatus());
    }

    @Test
    @DisplayName("质检 - 非 QC_PENDING 状态 → 抛出异常")
    void testSubmitQualityCheckWrongStatus() {
        PurchaseOrder order = new PurchaseOrder();
        order.setId(1L);
        order.setStatus("DRAFT");
        when(purchaseOrderMapper.selectById(1L)).thenReturn(order);

        assertThrows(IllegalStateException.class,
                () -> procurementService.submitQualityCheck(1L, 100, 5, "描述", "质检员"));
    }

    @Test
    @DisplayName("取消采购单 - 已提交 1688 → 调用 closeOrder 并更新状态")
    void testCancelPurchaseOrderWithAlibaba() {
        PurchaseOrder order = new PurchaseOrder();
        order.setId(1L);
        order.setAlibabaOrderNo("ALI-001");
        when(purchaseOrderMapper.selectById(1L)).thenReturn(order);

        boolean result = procurementService.cancelPurchaseOrder(1L);

        assertEquals(true, result);
        assertEquals("CANCELED", order.getStatus());
        verify(alibaba1688Client).closeOrder("ALI-001");
    }

    @Test
    @DisplayName("取消采购单 - 未提交 1688 → 仅更新状态")
    void testCancelPurchaseOrderWithoutAlibaba() {
        PurchaseOrder order = new PurchaseOrder();
        order.setId(1L);
        order.setAlibabaOrderNo(null);
        when(purchaseOrderMapper.selectById(1L)).thenReturn(order);

        boolean result = procurementService.cancelPurchaseOrder(1L);

        assertEquals(true, result);
        assertEquals("CANCELED", order.getStatus());
        verify(alibaba1688Client, never()).closeOrder(any());
    }
}
