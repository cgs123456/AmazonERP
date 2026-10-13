package com.amz.service.impl;

import com.amz.context.UserContext;
import com.amz.mapper.PurchaseInvoiceMapper;
import com.amz.mapper.PurchaseOrderMapper;
import com.amz.model.PurchaseInvoice;
import com.amz.model.PurchaseOrder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 采购发票（三单匹配第三单，A3）。
 *
 * <p>与 {@link SupplierPaymentService} 的分工：那边管「付款」，
 * 这边管「票」。付款前置校验里可以引用这里的核对结果，但两边不交叉写状态。
 */
@Slf4j
@Service
public class PurchaseInvoiceService {

    @Autowired
    private PurchaseInvoiceMapper invoiceMapper;

    @Autowired
    private PurchaseOrderMapper purchaseOrderMapper;

    /**
     * 登记发票。同店铺内发票号唯一（重复登记被拒，避免一张票对多个 PO 重复入账）。
     * <p>
     * {@code purchaseOrderId} 可空：供应商常先开票、后对账，允许先登记后关联。
     */
    public PurchaseInvoice register(Long shopId, PurchaseInvoice invoice) {
        if (invoice == null || invoice.getShopId() == null) {
            throw new IllegalArgumentException("shopId 不能为空");
        }
        if (invoice.getInvoiceNo() == null || invoice.getInvoiceNo().isBlank()) {
            throw new IllegalArgumentException("发票号不能为空");
        }
        if (invoice.getInvoiceAmount() == null
                || invoice.getInvoiceAmount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("发票金额必须大于 0");
        }
        invoice.setInvoiceNo(invoice.getInvoiceNo().trim());
        long dup = invoiceMapper.selectCount(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<PurchaseInvoice>()
                .eq(PurchaseInvoice::getShopId, invoice.getShopId())
                .eq(PurchaseInvoice::getInvoiceNo, invoice.getInvoiceNo()));
        if (dup > 0) {
            throw new IllegalArgumentException("该店铺已登记过发票号 " + invoice.getInvoiceNo());
        }
        // 关联的 PO 必须属于同一店铺，否则能拿别人的单绑自己的票
        if (invoice.getPurchaseOrderId() != null) {
            PurchaseOrder po = purchaseOrderMapper.selectById(invoice.getPurchaseOrderId());
            if (po == null || !po.getShopId().equals(invoice.getShopId())) {
                throw new IllegalArgumentException("关联的采购单不存在或不属于当前店铺");
            }
        }
        invoice.setStatus(PurchaseInvoice.STATUS_UNMATCHED);
        if (invoice.getTaxAmount() == null) {
            invoice.setTaxAmount(BigDecimal.ZERO);
        }
        if (invoice.getInvoiceDate() == null) {
            invoice.setInvoiceDate(LocalDate.now());
        }
        invoiceMapper.insert(invoice);
        log.info("登记采购发票 shopId={} invoiceNo={} amount={}",
                invoice.getShopId(), invoice.getInvoiceNo(), invoice.getInvoiceAmount());
        return invoice;
    }

    /** 按店铺列发票，可按关联的 PO 过滤。 */
    public List<PurchaseInvoice> list(Long shopId, Long purchaseOrderId) {
        return invoiceMapper.selectList(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<PurchaseInvoice>()
                .eq(PurchaseInvoice::getShopId, shopId)
                .eq(purchaseOrderId != null, PurchaseInvoice::getPurchaseOrderId, purchaseOrderId)
                .orderByDesc(PurchaseInvoice::getId));
    }

    /** 作废发票（管理性纠错）。已匹配的票作废要把 PO 上的关联解除的说法写进返回，调用方负责告知。 */
    public PurchaseInvoice cancel(Long shopId, Long id) {
        PurchaseInvoice invoice = invoiceMapper.selectById(id);
        if (invoice == null || !invoice.getShopId().equals(shopId)) {
            throw new IllegalArgumentException("发票不存在或无权访问");
        }
        if (PurchaseInvoice.STATUS_CANCELLED.equals(invoice.getStatus())) {
            throw new IllegalArgumentException("该发票已作废，请勿重复操作");
        }
        invoice.setStatus(PurchaseInvoice.STATUS_CANCELLED);
        invoiceMapper.updateById(invoice);
        return invoice;
    }

    /** 三方核对结果行（JSON 序列化给前端；不用枚举硬编码状态，文案随结论走）。 */
    public record ThreeWayMatch(Long purchaseOrderId, String purchaseOrderNo,
                                BigDecimal poAmount, BigDecimal receivedAmount,
                                BigDecimal invoiceAmount, int invoiceCount,
                                boolean poMatchesInvoice, boolean receivedMatchesInvoice,
                                String conclusion, String detail) {
    }

    /**
     * 三方核对：PO 金额 vs 已登记发票金额。
     * <p>
     * 容差口径与 {@code SupplierPaymentService} 保持一致（同一份配置），
     * 否则「这里说没问题、付款时又说超容差」会让使用者无所适从。
     * <p>
     * <b>诚实边界</b>：没有发票时结论是「缺发票」，不是「匹配」——
     * 把「还没核对」显示成「已通过」会让未开票的采购单直接进付款流程。
     */
    public ThreeWayMatch match(Long shopId, Long purchaseOrderId, BigDecimal tolerance) {
        PurchaseOrder po = purchaseOrderMapper.selectById(purchaseOrderId);
        if (po == null || !po.getShopId().equals(shopId)) {
            throw new IllegalArgumentException("采购单不存在或不属于当前店铺");
        }
        List<PurchaseInvoice> invoices = invoiceMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<PurchaseInvoice>()
                        .eq(PurchaseInvoice::getShopId, shopId)
                        .eq(PurchaseInvoice::getPurchaseOrderId, purchaseOrderId)
                        .ne(PurchaseInvoice::getStatus, PurchaseInvoice.STATUS_CANCELLED));

        BigDecimal poAmount = po.getTotalAmount() == null ? BigDecimal.ZERO : po.getTotalAmount();
        BigDecimal tol = tolerance == null ? new BigDecimal("0.01") : tolerance;

        if (invoices.isEmpty()) {
            return new ThreeWayMatch(po.getId(), po.getOrderNo(), poAmount, null, null, 0,
                    false, false, "MISSING_INVOICE",
                    "该采购单尚未登记发票：三单匹配缺「票」，付款前请先登记或确认供应商未开票");
        }
        BigDecimal invoiceTotal = invoices.stream()
                .map(PurchaseInvoice::getInvoiceAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        // 收货金额口径：PO 状态为已收货三态时按 PO 金额计（收货明细未建模为行级金额）
        BigDecimal receivedAmount = null;
        if (List.of("RECEIVED", "COMPLETED", "QC_PASSED").contains(po.getStatus())) {
            receivedAmount = poAmount;
        }

        boolean poMatches = within(poAmount, invoiceTotal, tol);
        boolean receivedMatches = receivedAmount != null && within(receivedAmount, invoiceTotal, tol);

        String conclusion;
        String detail;
        if (poMatches && receivedMatches) {
            conclusion = "MATCHED";
            detail = "PO 金额、收货金额与发票金额三方一致";
        } else if (poMatches) {
            conclusion = "PO_ONLY";
            detail = "PO 与发票一致，但采购单尚未收货（当前 " + po.getStatus()
                    + "）——货未到不能付款";
        } else {
            conclusion = "MISMATCH";
            detail = "PO 金额=" + poAmount + "，发票合计=" + invoiceTotal
                    + "，偏差超容差 " + tol.multiply(poAmount) + "。请核对后重试或调整容差";
        }
        return new ThreeWayMatch(po.getId(), po.getOrderNo(), poAmount, receivedAmount,
                invoiceTotal, invoices.size(), poMatches, receivedMatches, conclusion, detail);
    }

    /** 偏差是否在容差内。容差按 PO 金额的比例算（0.01 = 1%）。 */
    private static boolean within(BigDecimal base, BigDecimal actual, BigDecimal tolerance) {
        if (base == null || actual == null) {
            return false;
        }
        BigDecimal allowed = tolerance.multiply(base.abs());
        return actual.subtract(base).abs().compareTo(allowed) <= 0;
    }

    /** 未关联任何 PO 的发票（先登记后对账的那批）。 */
    public List<PurchaseInvoice> listUnlinked(Long shopId) {
        return invoiceMapper.selectList(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<PurchaseInvoice>()
                .eq(PurchaseInvoice::getShopId, shopId)
                .isNull(PurchaseInvoice::getPurchaseOrderId)
                .ne(PurchaseInvoice::getStatus, PurchaseInvoice.STATUS_CANCELLED)
                .orderByDesc(PurchaseInvoice::getId));
    }
}
