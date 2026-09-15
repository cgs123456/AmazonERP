package com.amz.controller;

import com.amz.annotation.RequireRole;
import com.amz.annotation.ShopScoped;
import com.amz.dto.PaymentCollectionSummary;
import com.amz.model.PaymentCollection;
import com.amz.result.Result;
import com.amz.service.PaymentCollectionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 订单级回款台账接口（T05）。
 */
@Slf4j
@RestController
@RequestMapping("/finance/collection")
public class PaymentCollectionController {

    @Autowired
    private PaymentCollectionService paymentCollectionService;

    /**
     * 查询回款台账（可按状态过滤：PENDING / IN_TRANSIT / SETTLED / REFUNDED / SHORTFALL）。
     */
    @ShopScoped
    @GetMapping("/list/{shopId}")
    public Result<List<PaymentCollection>> list(@PathVariable Long shopId,
                                                @RequestParam(required = false) String status) {
        try {
            return Result.success(paymentCollectionService.list(shopId, status));
        } catch (IllegalArgumentException e) {
            return Result.failure(e.getMessage());
        }
    }

    /**
     * 回款概览：在途未回金额与已回短款金额分别列示。
     */
    @ShopScoped
    @GetMapping("/summary/{shopId}")
    public Result<PaymentCollectionSummary> summary(@PathVariable Long shopId) {
        try {
            return Result.success(paymentCollectionService.summary(shopId));
        } catch (IllegalArgumentException e) {
            return Result.failure(e.getMessage());
        }
    }

    /**
     * 手动触发台账重算（结算数据落库后调用）。
     */
    @ShopScoped
    @RequireRole({"OPERATOR", "ADMIN"})
    @PostMapping("/rebuild")
    public Result<Integer> rebuild(@RequestParam Long shopId) {
        try {
            return Result.success(paymentCollectionService.rebuild(shopId));
        } catch (IllegalArgumentException e) {
            return Result.failure(e.getMessage());
        } catch (Exception e) {
            log.error("payment collection rebuild failed shopId={}", shopId, e);
            return Result.failure("回款台账重算失败：" + e.getMessage());
        }
    }
}
