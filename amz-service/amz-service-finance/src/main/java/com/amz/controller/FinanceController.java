package com.amz.controller;

import com.amz.model.AccountingVoucher;
import com.amz.result.Result;
import com.amz.service.FinanceService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;

/**
 * 业财一体化 REST 端点。
 */
@RestController
@RequestMapping("/finance")
public class FinanceController {

    @Autowired
    private FinanceService financeService;

    /**
     * 根据订单自动生成会计凭证（多币种换算）。
     * POST /finance/voucher/order
     */
    @PostMapping("/voucher/order")
    public Result<AccountingVoucher> generateOrderVoucher(
            @RequestParam Long shopId,
            @RequestParam String orderNo,
            @RequestParam BigDecimal amount,
            @RequestParam String currency) {
        return Result.success(financeService.generateOrderVoucher(shopId, orderNo, amount, currency));
    }

    /**
     * 同步凭证到金蝶。
     * POST /finance/voucher/{voucherId}/sync
     */
    @PostMapping("/voucher/{voucherId}/sync")
    public Result<Boolean> syncToKingdee(@PathVariable Long voucherId) {
        return Result.success(financeService.syncToKingdee(voucherId));
    }

    /**
     * 查询凭证列表。
     * GET /finance/voucher/list/{shopId}?sourceType=
     */
    @GetMapping("/voucher/list/{shopId}")
    public Result<List<AccountingVoucher>> listVouchers(
            @PathVariable Long shopId,
            @RequestParam(required = false) String sourceType) {
        return Result.success(financeService.listVouchers(shopId, sourceType));
    }

    /**
     * 查询店铺利润（CNY）。
     * GET /finance/profit/{shopId}?startDate=&endDate=
     */
    @GetMapping("/profit/{shopId}")
    public Result<BigDecimal> calculateProfit(
            @PathVariable Long shopId,
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate) {
        return Result.success(financeService.calculateProfit(shopId, startDate, endDate));
    }
}
