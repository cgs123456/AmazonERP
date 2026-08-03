package com.amz.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 广告搜索词报表实体。
 */
@Data
@TableName("amz_ad_search_term")
public class AdSearchTerm implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long shopId;
    private String campaignId;
    private String keywordId;
    private String searchTerm;
    private String matchType;
    private Long impressions;
    private Long clicks;
    private BigDecimal cost;
    private BigDecimal sales;
    private Integer orders;
    private BigDecimal acos;
    private BigDecimal cr;
    private BigDecimal ctr;
    private BigDecimal cpc;
    private LocalDate reportDate;
}
