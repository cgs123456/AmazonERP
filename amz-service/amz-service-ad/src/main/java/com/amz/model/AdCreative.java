package com.amz.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;

/**
 * SB 广告素材实体。
 * <p>
 * 支持素材类型：VIDEO / IMAGE / STORE_SPOTLIGHT / CUSTOM_HEADLINE
 * 状态流转：PENDING(待审核) → APPROVED(已通过) / REJECTED(已拒绝)
 */
@Data
@TableName("amz_ad_creative")
public class AdCreative implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    private String campaignId;

    /** 素材类型：VIDEO / IMAGE / STORE_SPOTLIGHT / CUSTOM_HEADLINE */
    private String creativeType;

    private String headline;

    private String brandName;

    private String logoUrl;

    private String videoUrl;

    private String landingPageUrl;

    /** 关联 ASIN */
    private String asin;

    /** 状态：PENDING / APPROVED / REJECTED */
    private String status;
}
