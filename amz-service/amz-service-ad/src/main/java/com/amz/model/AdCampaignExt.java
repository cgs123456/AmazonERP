package com.amz.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 广告活动扩展实体（支持 SP/SB/SD/DSP 全广告类型）。
 * <p>
 * ad_type: SP(Sponsored Products) / SB(Sponsored Brands) / SD(Sponsored Display) / DSP(Demand-Side Platform)
 */
@Data
@TableName("amz_ad_campaign_ext")
public class AdCampaignExt implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long shopId;

    private String campaignId;

    private String campaignName;

    /** 广告类型：SP / SB / SD / DSP */
    private String adType;

    /** 投放类型：SPONSORED_PRODUCTS / SPONSORED_BRANDS / SPONSORED_DISPLAY / DEMAND_SIDE_PLATFORM */
    private String campaignType;

    private BigDecimal budget;

    /** 预算类型：DAILY / LIFETIME */
    private String budgetType;

    /** 竞价策略 */
    private String biddingStrategy;

    /** 状态：ENABLED / PAUSED / ARCHIVED */
    private String status;

    private LocalDate startDate;

    private LocalDate endDate;

    /** 性能指标 */
    private Long impressions;

    private Long clicks;

    private BigDecimal spend;

    private BigDecimal sales;

    private Integer orders;

    private BigDecimal acos;

    private BigDecimal roas;
}
