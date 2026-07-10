package com.amz.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 补货建议实体。
 * <p>
 * 对应 amz_spapi.amz_replenishment_suggestion 表，由智能补货引擎按 SKU 维度生成。
 * 唯一键：shop_id + sku + stat_date。
 */
@Data
@TableName("amz_replenishment_suggestion")
public class ReplenishmentSuggestion {

    /**
     * 主键。
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 店铺 ID。
     */
    @TableField("shop_id")
    private Long shopId;

    /**
     * 卖家 SKU。
     */
    @TableField("sku")
    private String sku;

    /**
     * ASIN。
     */
    @TableField("asin")
    private String asin;

    /**
     * 建议生成日期。
     */
    @TableField("stat_date")
    private LocalDate statDate;

    /**
     * 当前总库存（available + inbound）。
     */
    @TableField("current_total_stock")
    private Integer currentTotalStock;

    /**
     * 基线需求（7天日均 × 0.7 + 30天日均 × 0.3）。
     */
    @TableField("baseline_demand")
    private BigDecimal baselineDemand;

    /**
     * 安全系数（基于 CV 变异系数：1.10 / 1.20 / 1.35）。
     */
    @TableField("safety_factor")
    private BigDecimal safetyFactor;

    /**
     * 季节性指数。
     */
    @TableField("seasonal_index")
    private BigDecimal seasonalIndex;

    /**
     * 促销乘数。
     */
    @TableField("promotion_multiplier")
    private BigDecimal promotionMultiplier;

    /**
     * 建议补货量。
     */
    @TableField("suggested_replenish_qty")
    private Integer suggestedReplenishQty;

    /**
     * 预计断货日期。
     */
    @TableField("estimated_stockout_date")
    private LocalDate estimatedStockoutDate;

    /**
     * 紧急程度：URGENT/NORMAL/LOW。
     */
    @TableField("urgency_level")
    private String urgencyLevel;

    /**
     * ML 预测需求量（null 表示未使用 ML）。
     */
    @TableField("ml_predicted_demand")
    private Double mlPredictedDemand;

    /**
     * ML 置信度（0.0-1.0，null 表示未使用 ML）。
     */
    @TableField("ml_confidence")
    private Double mlConfidence;

    /**
     * 混合策略描述：RULE_ONLY / HYBRID_ML_70_RULE_30。
     */
    @TableField("blend_strategy")
    private String blendStrategy;
}
