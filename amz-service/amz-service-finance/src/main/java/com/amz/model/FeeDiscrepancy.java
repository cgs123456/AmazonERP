package com.amz.model;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 费用差异 / 短收候选（索赔的输入）。
 * <p>
 * <b>金额口径</b>（三者关系固定，不可各自解读）：
 * <ul>
 *   <li>{@code expectedAmount} 应有金额 —— 费用类取费用预估；短收类取「按数量 × 成本应赔金额」</li>
 *   <li>{@code actualAmount} 实际发生金额 —— 费用类取实际扣费；短收类取平台已赔付金额（通常 0）</li>
 *   <li>{@code difference = actual - expected}：
 *       <b>正数 = 平台多收多扣</b>；<b>负数 = 应给未给（短收 / 未赔付）</b>。
 *       两种方向都是索赔候选，索赔金额取绝对值。</li>
 * </ul>
 * {@code evidence} 必须能让人工复核时独立判断（写明两边数字与来源），
 * 否则「系统说多收了 6.51」在申诉时无法举证。
 */
@Data
@TableName("amz_fee_discrepancy")
public class FeeDiscrepancy implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 配送费多收：实际 FBA 配送费高于费用预估。 */
    public static final String TYPE_FULFILLMENT_OVERCHARGE = "FULFILLMENT_FEE_OVERCHARGE";
    /** 佣金多收：实际佣金高于费用预估。 */
    public static final String TYPE_COMMISSION_OVERCHARGE = "COMMISSION_OVERCHARGE";
    /** 尺寸重测跳档：同一 SKU 的配送费出现阶梯跳变（重测后按更大尺寸档计费）。 */
    public static final String TYPE_SIZE_TIER_JUMP = "SIZE_TIER_JUMP";
    /** 入库短收：货件入库数量少于申报，平台尚未赔付。 */
    public static final String TYPE_INBOUND_SHORTAGE = "INBOUND_SHORTAGE";

    /** 候选（待提交索赔）。 */
    public static final String STATUS_CANDIDATE = "CANDIDATE";
    /** 已提交索赔。 */
    public static final String STATUS_CLAIMED = "CLAIMED";
    /** 已赔付。 */
    public static final String STATUS_REIMBURSED = "REIMBURSED";
    /** 已排除（复核后不成立）。 */
    public static final String STATUS_DISMISSED = "DISMISSED";

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long shopId;

    /** 卖家 SKU。 */
    private String sku;

    /** 关联货件编号（入库短收场景）。 */
    private String shipmentId;

    /** 差异类型，见本类常量。 */
    private String discrepancyType;

    /** 应有金额。 */
    private BigDecimal expectedAmount;

    /** 实际发生金额。 */
    private BigDecimal actualAmount;

    /** 差额 = 实际 - 应有（正 = 平台多收；负 = 应给未给）。 */
    private BigDecimal difference;

    private String currency;

    /** 举证说明：两边数字与来源，供人工复核与申诉使用。 */
    private String evidence;

    /** CANDIDATE / CLAIMED / REIMBURSED / DISMISSED。 */
    private String status;

    /** 识别时间。 */
    private LocalDateTime detectedAt;

    @TableField(value = "create_time", updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime createTime;

    @TableField(value = "update_time", updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime updateTime;
}
