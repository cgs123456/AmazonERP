package com.amz.dto;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 采购单 → PROCUREMENT 凭证生成报告。
 * <p>
 * 关键在 {@code remoteDegraded}：采购数据在另一个服务，取不到时如果按「0 张凭证」返回成功，
 * 利润就会把这一段的采购成本当成不存在——比不补凭证更糟。取不到必须显式标出来，
 * 由调用方决定重试还是人工核对。
 */
@Data
public class ProcurementVoucherReport {

    private Long shopId;

    /** 从采购域读到的成本可确认采购单行数 */
    private int scanned;

    /** 新生成的凭证数 */
    private int generated;

    /** 幂等命中（同 orderNo 已有凭证） */
    private int existing;

    /** 金额为空或为零：不出凭证，避免污染利润 */
    private int skippedZeroAmount;

    /** 缺单据号：没有幂等键，跳过而不是每次多记一张 */
    private int skippedNoOrderNo;

    /** 翻页读取的页数 */
    private int pagesRead;

    /** 达到最大页数上限：仍有数据未读，需要再跑一次 */
    private boolean capped;

    /** 采购域调用降级（未取到数据，generated 不代表完整结果） */
    private boolean remoteDegraded;

    /** 降级原因原文 */
    private String remoteMessage;

    /** 生成凭证的原币金额合计（CNY） */
    private BigDecimal originalAmountSum = BigDecimal.ZERO;
}
