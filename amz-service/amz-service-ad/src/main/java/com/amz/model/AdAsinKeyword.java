package com.amz.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * ASIN 关键词反查实体。
 */
@Data
@TableName("amz_ad_asin_keyword")
public class AdAsinKeyword implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long shopId;
    private String asin;
    private String keyword;
    private Integer organicRank;
    private Integer adRank;
    private Integer searchVolume;
    private BigDecimal relevanceScore;
    private Integer isIndexed;
    private LocalDate lastChecked;
}
