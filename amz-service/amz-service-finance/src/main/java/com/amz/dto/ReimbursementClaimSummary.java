package com.amz.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 索赔概览（T07）。
 * <p>
 * 两个关键比率都<b>只在有已结案样本时才给值</b>：
 * 「成功率」用 0 个样本算出 0% 会被读成「索赔全被驳回」，
 * 「平均赔付周期」同理 —— 没有样本时不返回数字，返回 null 并在 warnings 说明。
 */
@Data
public class ReimbursementClaimSummary {

    private Long shopId;

    private int total;
    private int candidate;
    private int submitted;
    private int accepted;
    private int reimbursed;
    private int rejected;

    /** 申请金额合计。 */
    private BigDecimal claimAmountTotal = BigDecimal.ZERO;
    /** 已赔付金额合计（真正要回来的钱）。 */
    private BigDecimal reimbursedAmountTotal = BigDecimal.ZERO;
    /** 在途申请金额（已提交未结案）。 */
    private BigDecimal inFlightAmount = BigDecimal.ZERO;

    /** 成功率 = 已赔付 / 已结案（赔付 + 驳回）；无已结案样本时为 null。 */
    private BigDecimal successRate;
    /** 平均赔付周期（天，提交 → 结案）；无已赔付样本时为 null。 */
    private BigDecimal avgSettlementDays;

    /** 按差异类型统计。 */
    private Map<String, TypeStat> byType = new LinkedHashMap<>();

    private List<String> warnings = new ArrayList<>();

    public void addWarning(String warning) {
        this.warnings.add(warning);
    }

    /**
     * 单类型统计。
     */
    @Data
    public static class TypeStat {
        private int claims;
        private int reimbursed;
        private BigDecimal claimAmount = BigDecimal.ZERO;
        private BigDecimal reimbursedAmount = BigDecimal.ZERO;
    }
}
