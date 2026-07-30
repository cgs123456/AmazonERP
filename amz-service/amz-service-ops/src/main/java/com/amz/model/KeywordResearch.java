package com.amz.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Date;

/**
 * 关键词调研实体。
 * 存储关键词搜索量、点击份额、转化份额、竞争难度等指标，用于选品调研参考。
 */
@Data
@TableName("amz_keyword_research")
public class KeywordResearch implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long shopId;

    private String keyword;

    private String marketplace;

    /** 月搜索量 */
    private Integer searchVolume;

    /** 点击份额% */
    private BigDecimal clickShare;

    /** 转化份额% */
    private BigDecimal conversionShare;

    /** Top 3 ASIN */
    private String topAsin;

    /** 竞争难度 0-100，越高越难 */
    private BigDecimal difficultyScore;

    /** 建议竞价 */
    private BigDecimal recommendedBid;

    private Date createTime;
}
