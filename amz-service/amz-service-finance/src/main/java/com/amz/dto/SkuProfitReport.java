package com.amz.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 单品真实利润报告（T09）。
 * <p>
 * <b>核心原则：缺失的数据绝不按 0 处理。</b>
 * 采购成本拿不到时按 0 计，会算出「利润率 80%」这种看起来很好、实际错误的结论，
 * 而且没人会去质疑一个好看的数。因此每个 SKU 都带 {@code costMissing} /
 * {@code profitIsComplete} 标记，盈利数字必须带着这个前提被解读。
 * <p>
 * 成本口径：{@code cogs = 售出件数 × 批次加权平均单位成本}。
 * 提示：严格 FIFO 需要「哪一批卖出」的批次级出库记录，当前批次域只提供库存批次，
 * 没有与销售的逐笔关联，故用加权平均近似 —— 这个近似值在举证与复核时必须说明。
 * <p>
 * 头程运费<b>不再单独加一遍</b>：批次成本里已含 freightCost（头程运费），
 * 再叠加物流模块的头程分摊会把同一笔运费计两次。
 */
@Data
public class SkuProfitReport {

    private Long shopId;
    private String depositAfter;
    private String depositBefore;

    private int skuCount;
    private List<SkuProfit> entries = new ArrayList<>();
    private SkuProfit totals = new SkuProfit();

    /** 是否所有 SKU 都拿到了成本数据。 */
    private boolean costDataComplete;
    /** 因成本数据缺失而利润不完整的 SKU 数。 */
    private int incompleteSkuCount;

    private List<String> warnings = new ArrayList<>();

    public void addWarning(String warning) {
        this.warnings.add(warning);
    }

    /**
     * 单品利润明细。
     */
    @Data
    public static class SkuProfit {
        private String sku;
        /** 售出件数（Order + Principal 行数）。 */
        private int unitsSold;

        /** 销售收入（Order + Principal）。 */
        private BigDecimal revenue = BigDecimal.ZERO;
        /** 平台佣金。 */
        private BigDecimal commission = BigDecimal.ZERO;
        /** FBA 配送费。 */
        private BigDecimal fulfillmentFee = BigDecimal.ZERO;
        /** 仓储费。 */
        private BigDecimal storageFee = BigDecimal.ZERO;
        /** 其他平台费用。 */
        private BigDecimal otherPlatformFees = BigDecimal.ZERO;
        /** 退款。 */
        private BigDecimal refunded = BigDecimal.ZERO;
        /** 平台赔付追回（含索赔追回）。 */
        private BigDecimal reimbursed = BigDecimal.ZERO;
        /** 销货成本（批次加权平均单位成本 × 售出件数）。 */
        private BigDecimal cogs = BigDecimal.ZERO;

        /** 净利润 = 收入 - 佣金 - 配送 - 仓储 - 其他费用 - 退款 - 销货成本 + 赔付追回。 */
        private BigDecimal profit = BigDecimal.ZERO;
        /** 单件利润。 */
        private BigDecimal profitPerUnit;
        /** 利润率 = 利润 / 收入；收入为 0 时为 null（不是 0%）。 */
        private BigDecimal marginRate;
        /** 加权平均单位成本（用于复核 COGS 计算）。 */
        private BigDecimal avgUnitCost;

        /** 是否缺少成本数据（true 时 profit 不含成本，不可当作真实利润）。 */
        private boolean costMissing;
        /** 利润是否完整（所有成本项齐备）。 */
        private boolean profitIsComplete;
        /** 口径说明（缺失项、近似口径等）。 */
        private List<String> dataNotes = new ArrayList<>();
    }
}
