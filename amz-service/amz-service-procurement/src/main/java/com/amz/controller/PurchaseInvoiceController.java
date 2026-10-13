package com.amz.controller;

import com.amz.annotation.OperLog;
import com.amz.annotation.RequireRole;
import com.amz.annotation.ShopScoped;
import com.amz.model.PurchaseInvoice;
import com.amz.result.Result;
import com.amz.service.impl.PurchaseInvoiceService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;

/**
 * 采购发票端点（三单匹配第三单，A3）。
 *
 * <p>与 {@code /procurement/payment} 的分工：那边管付款，这边管票。
 * 付款前置校验可引用本域的核对结论，但两边不交叉写状态。
 */
@Slf4j
@RestController
@RequestMapping("/procurement/invoice")
public class PurchaseInvoiceController {

    @Autowired
    private PurchaseInvoiceService invoiceService;

    /**
     * 付款金额相对 PO 金额的允许偏差。与 SupplierPaymentService 读同一份配置——
     * 两处各读一份同 key 的 @Value 比抽共享常量更直白，且 Spring 保证同 key 同值。
     */
    @Value("${amz.procurement.payment.amount-tolerance:0.01}")
    private BigDecimal amountTolerance;

    /**
     * 登记发票。同店铺内发票号唯一；purchaseOrderId 可空（先登记后关联）。
     */
    @ShopScoped
    @RequireRole({"OPERATOR", "ADMIN"})
    @OperLog(module = "采购发票", action = "登记", description = "登记供应商发票")
    @PostMapping
    public Result<PurchaseInvoice> register(@RequestBody PurchaseInvoice invoice) {
        try {
            return Result.success(invoiceService.register(invoice.getShopId(), invoice));
        } catch (Exception e) {
            log.warn("登记发票失败 shopId={}: {}", invoice == null ? null : invoice.getShopId(), e.getMessage());
            return Result.failure(e.getMessage());
        }
    }

    /**
     * 按店铺列发票，可按关联 PO 过滤。
     */
    @ShopScoped
    @GetMapping("/list/{shopId}")
    public Result<List<PurchaseInvoice>> list(@PathVariable Long shopId,
                                              @RequestParam(required = false) Long purchaseOrderId) {
        return Result.success(invoiceService.list(shopId, purchaseOrderId));
    }

    /**
     * 未关联任何 PO 的发票（先登记后对账的那批）。
     */
    @ShopScoped
    @GetMapping("/unlinked/{shopId}")
    public Result<List<PurchaseInvoice>> listUnlinked(@PathVariable Long shopId) {
        return Result.success(invoiceService.listUnlinked(shopId));
    }

    /**
     * 三方核对：PO 金额 vs 收货金额 vs 已登记发票金额。
     * <p>
     * 缺发票时结论是 MISSING_INVOICE 而不是「匹配」——把「还没核对」显示成
     * 「已通过」会让未开票的采购单直接进付款流程。
     */
    @ShopScoped
    @GetMapping("/match/{shopId}/{purchaseOrderId}")
    public Result<PurchaseInvoiceService.ThreeWayMatch> match(@PathVariable Long shopId,
                                                              @PathVariable Long purchaseOrderId) {
        try {
            return Result.success(invoiceService.match(shopId, purchaseOrderId, amountTolerance));
        } catch (Exception e) {
            log.warn("三方核对失败 shopId={} poId={}: {}", shopId, purchaseOrderId, e.getMessage());
            return Result.failure(e.getMessage());
        }
    }

    /**
     * 作废发票（管理性纠错）。已匹配的票作废后，该 PO 的三方核对会回到缺发票状态。
     */
    @ShopScoped
    @RequireRole({"OPERATOR", "ADMIN"})
    @OperLog(module = "采购发票", action = "作废", description = "作废采购发票")
    @PostMapping("/{id}/cancel")
    public Result<PurchaseInvoice> cancel(@PathVariable Long id, @RequestParam Long shopId) {
        try {
            return Result.success(invoiceService.cancel(shopId, id));
        } catch (Exception e) {
            log.warn("作废发票失败 shopId={} id={}: {}", shopId, id, e.getMessage());
            return Result.failure(e.getMessage());
        }
    }
}
