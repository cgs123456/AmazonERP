package com.amz.agent.selection;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 选品机会输入 DTO（AI 服务侧）。
 * <p>
 * 由于 amz-service-ops 的 SelectionOpportunity 实体不在 AI 服务依赖路径上，
 * 这里使用独立 DTO 接收 Feign 透传的请求体，字段名与 ops 侧实体保持一致。
 */
@Data
public class SelectionOpportunityInput {

    private String asin;
    private String title;
    private String category;
    private String marketplace;

    /** 平均售价 */
    private BigDecimal avgPrice;
    /** 平均评论数 */
    private Integer avgReviews;
    /** 平均评分 */
    private BigDecimal avgRating;
    /** 月搜索量 */
    private Integer searchVolume;

    /** 竞品数量 */
    private Integer competitorCount;
    /** 评论壁垒 LOW/MEDIUM/HIGH */
    private String reviewBarrier;
    /** 机会评分 0-100 */
    private BigDecimal opportunityScore;

    /** 30 天趋势 */
    private String trend30d;
    /** 90 天趋势 */
    private String trend90d;
}
