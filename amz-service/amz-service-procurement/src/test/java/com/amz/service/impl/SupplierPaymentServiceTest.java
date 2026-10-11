package com.amz.service.impl;

import com.amz.exception.CodeErrorException;
import com.amz.model.Bom;
import com.amz.model.SupplierPayment;
import com.amz.mapper.PurchaseOrderMapper;
import com.amz.mapper.SupplierPaymentMapper;
import com.amz.model.PurchaseOrder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 供应商付款契约（三单匹配，P2.2）。
 * <p>
 * <b>存在动因</b>：PO 的 PAID 状态自 V1 存在但全仓零写入点——「已付款」在数据上
 * 存在、行为上不存在；付款也没有任何单据留痕。本测试钉住三单匹配的边界：
 * <ol>
 *   <li><b>货到了才付钱</b>：PO 状态不在 RECEIVED/COMPLETED/QC_PASSED 一律拒绝；</li>
 *   <li><b>防重复付款</b>：同一 PO 只允许一张有效付款单；</li>
 *   <li><b>金额容差</b>：偏差超 1% 点名三方金额拒绝；</li>
 *   <li>付款成功 PO 状态推进为 PAID（唯一写入来源）；取消回退 RECEIVED。</li>
 * </ol>
 * 变异验证（写本测试时实际注入确认会红）：把收货证据校验删掉 → 第一例红；
 * 把容差比较改成恒过 → 第三例红。
 */
@DisplayName("供应商付款：三单匹配（货已收 + 防重复 + 金额容差）")
class SupplierPaymentServiceTest {

    @org.junit.jupiter.api.BeforeAll
    static void registerTableInfo() {
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(
                        new com.baomidou.mybatisplus.core.MybatisConfiguration(), ""),
                PurchaseOrder.class);
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(
                        new com.baomidou.mybatisplus.core.MybatisConfiguration(), ""),
                SupplierPayment.class);
    }

    @AfterEach
    void cleanup() {
        com.amz.context.UserContext.clear();
        com.amz.context.UserContext.setShops(List.of(9001L));
    }

    @Test
    @DisplayName("收货证据：DRAFT/SUBMITTED/SHIPPED 的 PO 不可付款")
    void rejectsUnreceivedPurchaseOrder() {
        SupplierPaymentMapper paymentMapper = mock(SupplierPaymentMapper.class);
        PurchaseOrderMapper poMapper = mock(PurchaseOrderMapper.class);
        SupplierPaymentService service = service(paymentMapper, poMapper);
        for (String status : List.of("DRAFT", "SUBMITTED", "PRODUCING", "SHIPPED", "PAID")) {
            PurchaseOrder po = po(31L, status, "1000.00");
            when(poMapper.selectById(31L)).thenReturn(po);
            CodeErrorException e = assertThrows(CodeErrorException.class,
                    () -> service.pay(31L, new BigDecimal("1000"), null, null));
            assertTrue(e.getMessage().contains("已收货"), "必须点名收货前提，实际：" + e.getMessage());
        }
        verify(paymentMapper, never()).insert(any(SupplierPayment.class));
    }

    @Test
    @DisplayName("防重复付款：同一 PO 已有有效付款单即拒绝")
    void rejectsDuplicatePayment() {
        SupplierPaymentMapper paymentMapper = mock(SupplierPaymentMapper.class);
        PurchaseOrderMapper poMapper = mock(PurchaseOrderMapper.class);
        SupplierPaymentService service = service(paymentMapper, poMapper);
        when(poMapper.selectById(32L)).thenReturn(po(32L, "RECEIVED", "1000.00"));
        when(paymentMapper.selectCount(any())).thenReturn(1L);

        CodeErrorException e = assertThrows(CodeErrorException.class,
                () -> service.pay(32L, new BigDecimal("1000"), null, null));
        assertTrue(e.getMessage().contains("不可重复付款"), e.getMessage());
        verify(paymentMapper, never()).insert(any(SupplierPayment.class));
    }

    @Test
    @DisplayName("金额容差：偏差超 1% 点名三方金额拒绝")
    void rejectsAmountBeyondTolerance() {
        SupplierPaymentMapper paymentMapper = mock(SupplierPaymentMapper.class);
        PurchaseOrderMapper poMapper = mock(PurchaseOrderMapper.class);
        SupplierPaymentService service = service(paymentMapper, poMapper);
        when(poMapper.selectById(33L)).thenReturn(po(33L, "RECEIVED", "1000.00"));
        when(paymentMapper.selectCount(any())).thenReturn(0L);

        CodeErrorException e = assertThrows(CodeErrorException.class,
                () -> service.pay(33L, new BigDecimal("1100"), null, null));
        assertTrue(e.getMessage().contains("PO 金额") && e.getMessage().contains("付款金额"),
                "必须点名三方金额，实际：" + e.getMessage());
        verify(paymentMapper, never()).insert(any(SupplierPayment.class));
    }

    @Test
    @DisplayName("付款成功：写付款单 + PO 状态推进 PAID")
    void paysAndAdvancesPoStatus() {
        SupplierPaymentMapper paymentMapper = mock(SupplierPaymentMapper.class);
        PurchaseOrderMapper poMapper = mock(PurchaseOrderMapper.class);
        SupplierPaymentService service = service(paymentMapper, poMapper);
        PurchaseOrder po = po(34L, "RECEIVED", "1000.00");
        when(poMapper.selectById(34L)).thenReturn(po);
        when(paymentMapper.selectCount(any())).thenReturn(0L);

        SupplierPayment payment = service.pay(34L, new BigDecimal("1000"), "ALIBABA", null);

        assertEquals(SupplierPayment.STATUS_PAID, payment.getStatus());
        assertEquals("PAID", po.getStatus(), "PAID 枚举值的唯一写入来源就是付款");
        verify(paymentMapper).insert(any(SupplierPayment.class));
        verify(poMapper).updateById(po);
    }

    @Test
    @DisplayName("取消付款：PO 状态回退 RECEIVED")
    void cancelRevertsPoStatus() {
        SupplierPaymentMapper paymentMapper = mock(SupplierPaymentMapper.class);
        PurchaseOrderMapper poMapper = mock(PurchaseOrderMapper.class);
        SupplierPaymentService service = service(paymentMapper, poMapper);
        SupplierPayment payment = new SupplierPayment();
        payment.setId(1L);
        payment.setShopId(9001L);
        payment.setPurchaseOrderId(35L);
        payment.setStatus(SupplierPayment.STATUS_PAID);
        when(paymentMapper.selectById(1L)).thenReturn(payment);
        PurchaseOrder po = po(35L, "PAID", "1000.00");
        when(poMapper.selectById(35L)).thenReturn(po);

        service.cancel(1L, "录错金额");

        assertEquals(SupplierPayment.STATUS_CANCELLED, payment.getStatus());
        assertEquals("RECEIVED", po.getStatus());
    }

    // ============ 辅助 ============

    private SupplierPaymentService service(SupplierPaymentMapper paymentMapper,
                                           PurchaseOrderMapper poMapper) {
        SupplierPaymentService service = new SupplierPaymentService();
        ReflectionTestUtils.setField(service, "paymentMapper", paymentMapper);
        ReflectionTestUtils.setField(service, "purchaseOrderMapper", poMapper);
        return service;
    }

    private PurchaseOrder po(Long id, String status, String totalAmount) {
        PurchaseOrder po = new PurchaseOrder();
        po.setId(id);
        po.setShopId(9001L);
        po.setOrderNo("PO-" + id);
        po.setSku("SKU-1");
        po.setQuantity(10);
        po.setStatus(status);
        po.setTotalAmount(new BigDecimal(totalAmount));
        return po;
    }
}
