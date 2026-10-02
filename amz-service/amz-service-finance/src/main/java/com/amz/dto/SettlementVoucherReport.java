package com.amz.dto;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 结算行 → 凭证生成报告。
 * <p>
 * 与 SettlementIngestReport 同思路：把「扫了多少行」「生成了几张」「为什么没生成」
 * 分开计数。只回一个「成功 N 张」会让「币种缺失被跳过」和「本来就没有费用行」
 * 长得一模一样，而这两种情况对账时要做的事完全相反。
 */
@Data
public class SettlementVoucherReport {

    private Long shopId;

    /** 本次扫描到的结算行数（受 cap 限制） */
    private int scanned;

    /** 生成的平台费用凭证数 */
    private int feeVouchers;

    /** 生成的退款凭证数 */
    private int refundVouchers;

    /** 幂等命中：同源凭证已存在，未重复插入 */
    private int existing;

    /** 归类为 PRINCIPAL/ADJUSTMENT，按口径不出凭证 */
    private int skippedByKind;

    /** 币种缺失：无法折算 CNY，跳过而不是按 1:1 硬编 */
    private int skippedNoCurrency;

    /** 金额为零：出凭证只会污染利润 */
    private int skippedZeroAmount;

    /** 生成的凭证原币金额合计（PLATFORM_FEE + REFUND） */
    private BigDecimal originalAmountSum = BigDecimal.ZERO;

    /** 命中扫描上限：仍有未扫的行，需要再跑一次 */
    private boolean capped;
}
