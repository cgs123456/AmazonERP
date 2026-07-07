package com.amz.model;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 广告报表数据（某周期内聚合指标）
 * <p>
 * 核心公式：
 * <ul>
 *   <li>ACoS = 广告花费 / 广告销售额 × 100%（越低越好）</li>
 *   <li>ROAS = 广告销售额 / 广告花费（ACoS 的倒数，越高越好）</li>
 *   <li>CTR  = 点击数 / 曝光数 × 100%</li>
 *   <li>CR   = 订单数 / 点击数 × 100%（转化率）</li>
 *   <li>CPC  = 广告花费 / 点击数（单次点击成本）</li>
 * </ul>
 */
@Data
public class AdReport {

    /** 广告活动 ID */
    private String campaignId;

    /** 关键词（关键词级报表时填，活动级报表为 null） */
    private String keyword;

    /** 曝光数 */
    private Long impressions;

    /** 点击数 */
    private Long clicks;

    /** 广告花费（美元） */
    private BigDecimal cost;

    /** 广告销售额（美元） */
    private BigDecimal sales;

    /** 订单数 */
    private Integer orders;

    /** ACoS（百分比，自动计算） */
    private BigDecimal acos;

    /** ROAS（自动计算） */
    private BigDecimal roas;

    /** CTR 点击率（自动计算） */
    private BigDecimal ctr;

    /** CR 转化率（自动计算） */
    private BigDecimal cr;

    /** CPC 单次点击成本（自动计算） */
    private BigDecimal cpc;
}
