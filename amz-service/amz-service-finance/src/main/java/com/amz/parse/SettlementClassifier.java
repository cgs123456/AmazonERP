package com.amz.parse;

import com.amz.model.SettlementDetail;

/**
 * 结算行归类（回款聚合与凭证生成共用的唯一口径）。
 * <p>
 * 亚马逊结算报表的符号约定：扣项为负、返还为正。历史上「回款」和「凭证」两套
 * 各自写过一遍 if 链，一旦其中一处补了类型分支，两处金额就开始互相打脸。
 * 规则收在这里，两处都必须走它。
 */
public final class SettlementClassifier {

    /** 归类结果。与 SettlementDetail.transactionType / amountType 的组合一一对应。 */
    public enum Kind {
        /** Order + Principal：销售本金，计入应收（收入侧凭证另有来源） */
        PRINCIPAL,
        /** Order 下非 Principal（佣金、配送费等）与 ServiceFee/未知类型：平台扣费 */
        FEE,
        /** Refund：退款（对卖家是负向） */
        REFUND,
        /** Adjustment：调整/赔付，计入已赔付追回 */
        ADJUSTMENT
    }

    private SettlementClassifier() {
    }

    public static Kind classify(SettlementDetail row) {
        if (row == null) {
            throw new IllegalArgumentException("结算行不能为空");
        }
        String type = row.getTransactionType() == null ? "" : row.getTransactionType();
        if ("Order".equalsIgnoreCase(type)) {
            return "Principal".equalsIgnoreCase(row.getAmountType()) ? Kind.PRINCIPAL : Kind.FEE;
        }
        if ("Refund".equalsIgnoreCase(type)) {
            return Kind.REFUND;
        }
        if ("Adjustment".equalsIgnoreCase(type)) {
            return Kind.ADJUSTMENT;
        }
        // ServiceFee 及其它类型：按扣费处理（负数为扣，正数为返还）
        return Kind.FEE;
    }

    /**
     * 该归类是否会生成凭证。
     * <p>
     * PRINCIPAL 由订单凭证覆盖（MQ 消费者生成），ADJUSTMENT 属索赔链路，
     * 都在这里显式排除，避免同一笔钱被两个来源重复计入利润。
     */
    public static boolean voucherizable(Kind kind) {
        return kind == Kind.FEE || kind == Kind.REFUND;
    }
}
