package com.amz.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 广告关键词实体
 */
@Data
@TableName("amz_ad_keyword")
public class AdKeyword implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属广告活动 ID */
    private String campaignId;

    /** 所属店铺 ID */
    private Long shopId;

    /** 关键词文本 */
    private String keyword;

    /** 匹配类型：EXACT / PHRASE / BROAD */
    private String matchType;

    /** 当前竞价（美元） */
    private BigDecimal bid;

    /** 状态：ENABLED / PAUSED */
    private String state;
}
