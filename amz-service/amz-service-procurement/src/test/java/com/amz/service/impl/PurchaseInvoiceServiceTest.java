package com.amz.service.impl;

import com.amz.mapper.PurchaseInvoiceMapper;
import com.amz.mapper.PurchaseOrderMapper;
import com.amz.model.PurchaseInvoice;
import com.amz.model.PurchaseOrder;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 采购发票与三方核对契约（A3）。
 *
 * <p>最关键一条：**缺发票时结论必须是 MISSING_INVOICE，不能是「匹配」**。
 * 把「还没核对」显示成「已通过」会让未开票的采购单直接进付款流程——
 * 那是把钱付出去而没有任何票据凭据。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("采购发票：三方核对的诚实边界、唯一性与店铺隔离")
class PurchaseInvoiceServiceTest {

    @Mock
    private PurchaseInvoiceMapper invoiceMapper;

    @Mock
    private PurchaseOrderMapper purchaseOrderMapper;

    private PurchaseInvoiceService service;

    @BeforeAll
    static void initTableInfo() {
        // MyBatis-Plus 单测前置：不初始化表信息，LambdaQueryWrapper 抛
        // 「can not find lambda cache」（本仓既有单测同样先做这一步）
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, PurchaseInvoice.class);
        TableInfoHelper.initTableInfo(assistant, PurchaseOrder.class);
    }

    @BeforeEach
    void setUp() {
        service = new PurchaseInvoiceService();
        ReflectionTestUtils.setField(service, "invoiceMapper", invoiceMapper);
        ReflectionTestUtils.setField(service, "purchaseOrderMapper", purchaseOrderMapper);
    }

    private static PurchaseOrder po(Long id, Long shopId, String status, String amount) {
        PurchaseOrder po = new PurchaseOrder();
        po.setId(id);
        po.setShopId(shopId);
        po.setOrderNo("PO-" + id);
        po.setStatus(status);
        po.setTotalAmount(new BigDecimal(amount));
        return po;
    }

    private static PurchaseInvoice invoice(String no, Long poId, String amount) {
        PurchaseInvoice inv = new PurchaseInvoice();
        inv.setInvoiceNo(no);
        inv.setPurchaseOrderId(poId);
        inv.setInvoiceAmount(new BigDecimal(amount));
        return inv;
    }

    @Test
    @DisplayName("缺发票时结论是 MISSING_INVOICE，绝不是「匹配」")
    void missingInvoiceIsNotAMatch() {
        when(purchaseOrderMapper.selectById(31L)).thenReturn(po(31L, 1L, "RECEIVED", "2200.00"));
        when(invoiceMapper.selectList(any())).thenReturn(List.of());

        PurchaseInvoiceService.ThreeWayMatch result = service.match(1L, 31L, new BigDecimal("0.01"));

        assertEquals("MISSING_INVOICE", result.conclusion());
        assertFalse(result.poMatchesInvoice());
        assertFalse(result.receivedMatchesInvoice());
        assertEquals(0, result.invoiceCount());
        assertNotNull(result.detail());
        assertTrue(result.detail().contains("尚未登记发票"), result.detail());
    }

    @Test
    @DisplayName("PO、收货、发票三方一致时结论 MATCHED")
    void threeWayMatched() {
        when(purchaseOrderMapper.selectById(31L)).thenReturn(po(31L, 1L, "RECEIVED", "2200.00"));
        when(invoiceMapper.selectList(any())).thenReturn(List.of(invoice("INV-1", 31L, "2200.00")));

        PurchaseInvoiceService.ThreeWayMatch result = service.match(1L, 31L, new BigDecimal("0.01"));

        assertEquals("MATCHED", result.conclusion());
        assertTrue(result.poMatchesInvoice());
        assertTrue(result.receivedMatchesInvoice());
        assertEquals(new BigDecimal("2200.00"), result.invoiceAmount());
    }

    @Test
    @DisplayName("金额偏差超容差：结论 MISMATCH 且点名 PO 金额与发票合计")
    void mismatchBeyondTolerance() {
        when(purchaseOrderMapper.selectById(31L)).thenReturn(po(31L, 1L, "RECEIVED", "2200.00"));
        when(invoiceMapper.selectList(any())).thenReturn(List.of(invoice("INV-1", 31L, "3000.00")));

        PurchaseInvoiceService.ThreeWayMatch result = service.match(1L, 31L, new BigDecimal("0.01"));

        assertEquals("MISMATCH", result.conclusion());
        assertFalse(result.poMatchesInvoice());
        // 原因里必须能看出差多少、容差多少，否则使用者不知道该改哪边
        assertTrue(result.detail().contains("2200.00"), result.detail());
        assertTrue(result.detail().contains("3000.00"), result.detail());
        assertTrue(result.detail().contains("22.00"), result.detail());
    }

    @Test
    @DisplayName("票与 PO 一致但货未到：结论 PO_ONLY，不能算通过")
    void invoiceMatchesButGoodsNotReceived() {
        when(purchaseOrderMapper.selectById(31L)).thenReturn(po(31L, 1L, "IN_TRANSIT", "2200.00"));
        when(invoiceMapper.selectList(any())).thenReturn(List.of(invoice("INV-1", 31L, "2200.00")));

        PurchaseInvoiceService.ThreeWayMatch result = service.match(1L, 31L, new BigDecimal("0.01"));

        assertEquals("PO_ONLY", result.conclusion());
        assertTrue(result.poMatchesInvoice());
        assertFalse(result.receivedMatchesInvoice());
        assertTrue(result.detail().contains("尚未收货"), result.detail());
    }

    @Test
    @DisplayName("同一店铺重复发票号被拒，且不落库")
    void duplicateInvoiceNumberRejected() {
        when(invoiceMapper.selectCount(any())).thenReturn(1L);

        PurchaseInvoice dup = invoice("INV-DUP", 31L, "100.00");
        dup.setShopId(1L);

        assertThrows(IllegalArgumentException.class, () -> service.register(1L, dup));
        verify(invoiceMapper, never()).insert(any(PurchaseInvoice.class));
    }

    @Test
    @DisplayName("关联的 PO 不属于当前店铺时拒绝——不能拿别人的单绑自己的票")
    void crossShopPurchaseOrderRejected() {
        when(invoiceMapper.selectCount(any())).thenReturn(0L);
        when(purchaseOrderMapper.selectById(999L)).thenReturn(po(999L, 2L, "RECEIVED", "100.00"));

        PurchaseInvoice inv = invoice("INV-X", 999L, "100.00");
        inv.setShopId(1L);

        assertThrows(IllegalArgumentException.class, () -> service.register(1L, inv));
        verify(invoiceMapper, never()).insert(any(PurchaseInvoice.class));
    }

    @Test
    @DisplayName("发票号为空或金额非正数被拒")
    void registerValidatesRequiredFields() {
        PurchaseInvoice noNo = invoice("", 31L, "100.00");
        noNo.setShopId(1L);
        assertThrows(IllegalArgumentException.class, () -> service.register(1L, noNo));

        PurchaseInvoice zero = invoice("INV-0", 31L, "0");
        zero.setShopId(1L);
        when(invoiceMapper.selectCount(any())).thenReturn(0L);
        assertThrows(IllegalArgumentException.class, () -> service.register(1L, zero));
    }

    @Test
    @DisplayName("三方核对时跨店铺的 PO 查不到")
    void matchRejectsForeignPurchaseOrder() {
        when(purchaseOrderMapper.selectById(31L)).thenReturn(po(31L, 2L, "RECEIVED", "2200.00"));

        assertThrows(IllegalArgumentException.class, () -> service.match(1L, 31L, new BigDecimal("0.01")));
    }

    @Test
    @DisplayName("登记成功时默认未匹配状态、税额兜底 0、开票日期兜底今天")
    void registerFillsDefaults() {
        when(invoiceMapper.selectCount(any())).thenReturn(0L);
        // 关联了 PO 就要让它查得到（漏这句桩会被「PO 不存在」挡住，测不到默认值填充）
        when(purchaseOrderMapper.selectById(31L)).thenReturn(po(31L, 1L, "RECEIVED", "2200.00"));

        PurchaseInvoice inv = invoice("INV-OK", 31L, "2200.00");
        inv.setShopId(1L);
        service.register(1L, inv);

        ArgumentCaptor<PurchaseInvoice> captor = ArgumentCaptor.forClass(PurchaseInvoice.class);
        verify(invoiceMapper).insert(captor.capture());
        PurchaseInvoice saved = captor.getValue();
        assertEquals(PurchaseInvoice.STATUS_UNMATCHED, saved.getStatus());
        assertEquals(BigDecimal.ZERO, saved.getTaxAmount());
        assertNotNull(saved.getInvoiceDate());
    }
}
