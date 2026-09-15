package com.amz.service;

import com.amz.dto.ReimbursementClaimSummary;
import com.amz.dto.ReimbursementReconcileReport;
import com.amz.model.ReimbursementClaim;

import java.math.BigDecimal;
import java.util.List;

/**
 * 索赔单服务（T07）与追回入账（T08）。
 */
public interface ReimbursementClaimService {

    /**
     * 由差异候选生成索赔单（CANDIDATE 状态），并把候选置为 CLAIMED。
     * <p>
     * 幂等：同一候选重复调用返回既有索赔单 ID，不重复建单。
     *
     * @return 索赔单 ID
     */
    Long createFromDiscrepancy(Long shopId, Long discrepancyId);

    /** CANDIDATE → SUBMITTED。 */
    ReimbursementClaim submit(Long shopId, Long id);

    /** SUBMITTED → ACCEPTED。 */
    ReimbursementClaim accept(Long shopId, Long id);

    /**
     * ACCEPTED → REIMBURSED，并生成追回入账凭证（T08）。
     * <p>
     * 赔付金额允许小于申请金额（平台常部分赔付），差额即损失。
     * 已赔付状态重复调用幂等返回，不会重复生成凭证。
     */
    ReimbursementClaim reimburse(Long shopId, Long id, BigDecimal reimbursedAmount);

    /** SUBMITTED / ACCEPTED → REJECTED。 */
    ReimbursementClaim reject(Long shopId, Long id, String reason);

    List<ReimbursementClaim> list(Long shopId, String status);

    /** 按 ID 查询（含租户归属校验，越权返回 null）。 */
    ReimbursementClaim get(Long shopId, Long id);

    ReimbursementClaimSummary summary(Long shopId);

    /**
     * 索赔与平台赔付双向核对（T08）。
     *
     * @param depositAfter  存款日起始（ISO 8601 字符串，可为 null）
     * @param depositBefore 存款日截止（ISO 8601 字符串，可为 null）
     */
    ReimbursementReconcileReport reconcile(Long shopId, String depositAfter, String depositBefore);
}
