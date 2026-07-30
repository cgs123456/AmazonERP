package com.amz.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 选品分析结果实体。
 * 存储基于关键词分析得到的蓝海机会商品，包含市场指标、竞争指标、趋势和 AI 建议。
 */
@Data
@TableName("amz_selection_opportunity")
public class SelectionOpportunity implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long shopId;

    /** 商品 ASIN */
    private String asin;

    private String title;

    private String category;

    /** 站点代码，默认 US */
    private String marketplace;

    // ===== 市场指标 =====

    /** 平均售价 */
    private BigDecimal avgPrice;

    /** 平均评论数 */
    private Integer avgReviews;

    /** 平均评分 0-5 */
    private BigDecimal avgRating;

    /** 月搜索量 */
    private Integer searchVolume;

    // ===== 竞争指标 =====

    /** 竞品数量 */
    private Integer competitorCount;

    /** 评论壁垒 LOW/MEDIUM/HIGH */
    private String reviewBarrier;

    /** 机会评分 0-100，越高越好 */
    private BigDecimal opportunityScore;

    // ===== 趋势 =====

    /** 30 天趋势 UP/FLAT/DOWN */
    private String trend30d;

    /** 90 天趋势 UP/FLAT/DOWN */
    private String trend90d;

    // ===== AI 建议 =====

    /** AI 分析摘要 */
    private String aiSummary;

    /** AI 详细建议：是否进入/定价策略/差异化方向/首批采购量 */
    private String aiSuggestion;

    /** 状态：ANALYZED/AI_SUGGESTED/ARCHIVED */
    private String status;

    private java.util.Date createTime;

    private java.util.Date updateTime;
}
