package com.amz.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 广告活动实体（对应 Amazon Advertising API 的 Campaign）
 */
@Data
@TableName("amz_ad_campaign")
public class AdCampaign implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    /** Amazon 广告活动 ID */
    private String campaignId;

    /** 所属店铺 ID */
    private Long shopId;

    /** 活动名称 */
    private String name;

    /** 投放类型：SP(Sponsored Products) / SB(Sponsored Brands) / SD(Sponsored Display) */
    private String campaignType;

    /** 状态：ENABLED / PAUSED / ARCHIVED */
    private String state;

    /** 日预算（美元） */
    private BigDecimal dailyBudget;

    /** 竞价策略：LEGACY_FOR_SALES / FOR_SALES / FOR_VISIBILITY */
    private String biddingStrategy;
}
