package com.amz.controller;

import com.amz.annotation.RequireRole;
import com.amz.annotation.ShopScoped;
import com.amz.dto.ReimbursementClaimSummary;
import com.amz.dto.ReimbursementReconcileReport;
import com.amz.model.ReimbursementClaim;
import com.amz.result.Result;
import com.amz.service.ReimbursementClaimService;
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
 * 索赔单与追回入账接口（T07 / T08）。
 * <p>
 * 鉴权：只读 {@code @ShopScoped}；建单、状态推进、赔付全部加 {@code @RequireRole}。
 * id 型状态的归属校验在服务层 —— 这类端点不带 shopId，
 * 若不校验归属，改个数字就能推进他店索赔单（越权写入）。
 */
@Slf4j
@RestController
@RequestMapping("/finance/claim")
public class ReimbursementClaimController {

    @Autowired
    private ReimbursementClaimService reimbursementClaimService;

    /**
     * 由差异候选生成索赔单。
     */
    @ShopScoped
    @RequireRole({"OPERATOR", "ADMIN"})
    @PostMapping("/from-discrepancy")
    public Result<Long> createFromDiscrepancy(@RequestParam Long shopId,
                                              @RequestParam Long discrepancyId) {
        try {
            return Result.success(reimbursementClaimService.createFromDiscrepancy(shopId, discrepancyId));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return Result.failure(e.getMessage());
        } catch (Exception e) {
            log.error("create claim failed shopId={} discrepancyId={}", shopId, discrepancyId, e);
            return Result.failure("索赔单创建失败：" + e.getMessage());
        }
    }

    /**
     * 提交索赔（CANDIDATE → SUBMITTED）。
     */
    @ShopScoped
    @RequireRole({"OPERATOR", "ADMIN"})
    @PostMapping("/{id}/submit")
    public Result<ReimbursementClaim> submit(@PathVariable Long id, @RequestParam Long shopId) {
        return advance(() -> reimbursementClaimService.submit(shopId, id));
    }

    /**
     * 平台已受理（SUBMITTED → ACCEPTED）。
     */
    @ShopScoped
    @RequireRole({"OPERATOR", "ADMIN"})
    @PostMapping("/{id}/accept")
    public Result<ReimbursementClaim> accept(@PathVariable Long id, @RequestParam Long shopId) {
        return advance(() -> reimbursementClaimService.accept(shopId, id));
    }

    /**
     * 确认赔付（ACCEPTED → REIMBURSED），同步生成追回入账凭证。
     */
    @ShopScoped
    @RequireRole({"OPERATOR", "ADMIN"})
    @PostMapping("/{id}/reimburse")
    public Result<ReimbursementClaim> reimburse(@PathVariable Long id,
                                                @RequestParam Long shopId,
                                                @RequestParam BigDecimal reimbursedAmount) {
        return advance(() -> reimbursementClaimService.reimburse(shopId, id, reimbursedAmount));
    }

    /**
     * 驳回（SUBMITTED / ACCEPTED → REJECTED）。
     */
    @ShopScoped
    @RequireRole({"OPERATOR", "ADMIN"})
    @PostMapping("/{id}/reject")
    public Result<ReimbursementClaim> reject(@PathVariable Long id,
                                             @RequestParam Long shopId,
                                             @RequestParam(required = false) String reason) {
        return advance(() -> reimbursementClaimService.reject(shopId, id, reason));
    }

    /**
     * 索赔单列表（可按状态过滤）。
     */
    @ShopScoped
    @GetMapping("/list/{shopId}")
    public Result<List<ReimbursementClaim>> list(@PathVariable Long shopId,
                                                 @RequestParam(required = false) String status) {
        try {
            return Result.success(reimbursementClaimService.list(shopId, status));
        } catch (IllegalArgumentException e) {
            return Result.failure(e.getMessage());
        }
    }

    /**
     * 索赔单详情。
     */
    @ShopScoped
    @GetMapping("/{id}")
    public Result<ReimbursementClaim> detail(@PathVariable Long id, @RequestParam Long shopId) {
        ReimbursementClaim c = reimbursementClaimService.get(shopId, id);
        if (c == null) {
            return Result.failure("索赔单不存在或无权访问");
        }
        return Result.success(c);
    }

    /**
     * 索赔概览（成功率与赔付周期无样本时返回 null 而非 0）。
     */
    @ShopScoped
    @GetMapping("/summary/{shopId}")
    public Result<ReimbursementClaimSummary> summary(@PathVariable Long shopId) {
        try {
            return Result.success(reimbursementClaimService.summary(shopId));
        } catch (IllegalArgumentException e) {
            return Result.failure(e.getMessage());
        }
    }

    /**
     * 索赔与平台赔付双向核对（T08）。
     */
    @ShopScoped
    @GetMapping("/reconcile/{shopId}")
    public Result<ReimbursementReconcileReport> reconcile(@PathVariable Long shopId,
                                                          @RequestParam(required = false) String depositAfter,
                                                          @RequestParam(required = false) String depositBefore) {
        try {
            return Result.success(reimbursementClaimService.reconcile(shopId, depositAfter, depositBefore));
        } catch (IllegalArgumentException e) {
            return Result.failure(e.getMessage());
        }
    }

    /** 状态推进类接口的公共异常处理（状态机拒绝 / 越权 / 参数错误都转成可读提示）。 */
    private static Result<ReimbursementClaim> advance(java.util.function.Supplier<ReimbursementClaim> action) {
        try {
            return Result.success(action.get());
        } catch (IllegalArgumentException | IllegalStateException e) {
            return Result.failure(e.getMessage());
        } catch (Exception e) {
            log.error("claim state transition failed", e);
            return Result.failure("索赔单状态推进失败：" + e.getMessage());
        }
    }
}
