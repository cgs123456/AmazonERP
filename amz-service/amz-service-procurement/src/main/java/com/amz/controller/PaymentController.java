package com.amz.controller;

import com.amz.annotation.OperLog;
import com.amz.annotation.RequireRole;
import com.amz.annotation.ShopScoped;
import com.amz.model.SupplierPayment;
import com.amz.result.Result;
import com.amz.service.impl.SupplierPaymentService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;

/**
 * 供应商付款接口（三单匹配闭环，P2.2）。
 * <p>
 * 付款前置：PO 状态 ∈ RECEIVED/COMPLETED/QC_PASSED（货已收）+ 金额容差内。
 * 付款成功把 PO 状态推进为 PAID（该枚举值的唯一写入来源）。
 */
@Slf4j
@RestController
@RequestMapping("/procurement/payment")
public class PaymentController {

    @Autowired
    private SupplierPaymentService supplierPaymentService;

    /**
     * 付款（三单匹配通过后执行）。
     */
    @ShopScoped
    @RequireRole({"OPERATOR", "ADMIN"})
    @OperLog(module = "采购付款", action = "付款", description = "供应商付款并推进 PO 为 PAID")
    @PostMapping
    public Result<SupplierPayment> pay(@RequestParam Long purchaseOrderId,
                                       @RequestParam BigDecimal amount,
                                       @RequestParam(required = false) String method,
                                       @RequestParam(required = false) String remark) {
        try {
            return Result.success(supplierPaymentService.pay(purchaseOrderId, amount, method, remark));
        } catch (Exception e) {
            log.warn("付款失败 purchaseOrderId={}: {}", purchaseOrderId, e.getMessage());
            return Result.failure(e.getMessage());
        }
    }

    /**
     * 取消付款（管理性纠错）：PO 状态回退 RECEIVED。
     */
    @ShopScoped
    @RequireRole({"ADMIN"})
    @OperLog(module = "采购付款", action = "取消", description = "取消付款并回退 PO 状态")
    @PostMapping("/{id}/cancel")
    public Result<SupplierPayment> cancel(@PathVariable Long id,
                                          @RequestParam(required = false) String reason) {
        try {
            return Result.success(supplierPaymentService.cancel(id, reason));
        } catch (Exception e) {
            log.warn("取消付款失败 id={}: {}", id, e.getMessage());
            return Result.failure(e.getMessage());
        }
    }

    /**
     * 付款单列表。
     */
    @ShopScoped
    @GetMapping("/list/{shopId}")
    public Result<List<SupplierPayment>> list(@PathVariable Long shopId) {
        try {
            return Result.success(supplierPaymentService.list(shopId));
        } catch (Exception e) {
            log.warn("查询付款单失败 shopId={}: {}", shopId, e.getMessage());
            return Result.failure(e.getMessage());
        }
    }
}
