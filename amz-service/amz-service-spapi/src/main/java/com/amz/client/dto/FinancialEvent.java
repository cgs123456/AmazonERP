package com.amz.client.dto;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 财务事件（来自 SP-API Finances API，经统一解析后的业务口径）。
 * <p>
 * 类型分四类：INCOME（销售_principal 收入）/ REFUND（退款）/
 * FEE（佣金、FBA 配送费等平台扣费）/ ADJUSTMENT（平台调整，含 FBA 库存赔付）。
 * <p>
 * {@link #amount} 为<b>有符号</b>金额：收入与正向调整为正，退款与平台扣费为负 ——
 * 直接求和即得净影响，避免上层各自维护符号约定。
 * <p>
 * {@link #eventId} 为确定性幂等键（type:orderId:postedAt:feeType:index），
 * 同一事件重复拉取不产生重复记录。
 */
@Data
public class FinancialEvent {

    /** 销售收入（Principal）。 */
    public static final String TYPE_INCOME = "INCOME";
    /** 退款（负向）。 */
    public static final String TYPE_REFUND = "REFUND";
    /** 平台扣费：佣金 / FBA 配送费等（发货场景为负；退款场景的费用返还为正）。 */
    public static final String TYPE_FEE = "FEE";
    /** 平台调整：FBA 库存赔付、争议裁决等（可正可负）。 */
    public static final String TYPE_ADJUSTMENT = "ADJUSTMENT";

    /** 确定性幂等键。 */
    private String eventId;
    /** INCOME / REFUND / FEE / ADJUSTMENT。 */
    private String type;
    /** 关联亚马逊订单号（调整事件可能为空）。 */
    private String amazonOrderId;
    /** 卖家 SKU。 */
    private String sku;
    /** ASIN。 */
    private String asin;
    /** 入账时间（ISO 8601，UTC）。 */
    private String postedAt;
    /** 有符号金额（保留 SP-API 原始方向）：收入与赔付为正，退款与发货扣费为负，退款费用返还为正。 */
    private BigDecimal amount;
    /** 币种（ISO 4217）。 */
    private String currency;
    /** 费用或调整类型（Commission / FBAFulfillmentFee / FBA Inventory Reimbursement 等）。 */
    private String feeType;
    /** 描述。 */
    private String description;
}
