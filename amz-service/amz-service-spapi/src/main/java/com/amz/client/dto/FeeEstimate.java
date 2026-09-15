package com.amz.client.dto;

import lombok.Data;

import java.math.BigDecimal;

/**
 * FBA 费用预估（SP-API Fees API v0 的简化业务口径）。
 * <p>
 * 只拆出索赔场景最关心的两项：佣金（ReferralFee）与 FBA 配送费（FBAFulfillmentFee），
 * 其余小额费用并入 {@link #otherFees} ——
 * 与实际扣费比对时按「总额 + 两大项」即可定位多数差异来源。
 */
@Data
public class FeeEstimate {

    private String asin;
    private String sku;
    /** 用于估价的售价。 */
    private BigDecimal price;
    private String currency;
    /** 佣金。 */
    private BigDecimal referralFee;
    /** FBA 配送费。 */
    private BigDecimal fulfillmentFee;
    /** 其余费用（小额杂项合计）。 */
    private BigDecimal otherFees;
    /** 费用合计 = referral + fulfillment + other。 */
    private BigDecimal totalFees;
    /** 预估净得 = price - totalFees。 */
    private BigDecimal estimatedNet;
}
