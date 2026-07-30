package com.amz.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * SD 受众定向实体。
 * <p>
 * targeting_type: CONTEXTUAL(上下文定向) / REMARKETING(再营销) / AUDIENCE(受众) / LOOKALIKE(相似人群)
 */
@Data
@TableName("amz_ad_targeting")
public class AdTargeting implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    private String campaignId;

    /** 定向类型：CONTEXTUAL / REMARKETING / AUDIENCE / LOOKALIKE */
    private String targetingType;

    /** 定向值：ASIN / CATEGORY / INTEREST */
    private String targetingValue;

    private BigDecimal bid;

    private Long impressions;

    private Long clicks;

    private BigDecimal spend;

    private BigDecimal sales;
}
