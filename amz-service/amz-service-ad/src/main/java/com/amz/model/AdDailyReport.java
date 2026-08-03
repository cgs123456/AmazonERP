package com.amz.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 广告日报表实体。
 */
@Data
@TableName("amz_ad_daily_report")
public class AdDailyReport implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long shopId;
    private String campaignId;
    private String adType;
    private LocalDate reportDate;
    private Long impressions;
    private Long clicks;
    private BigDecimal cost;
    private BigDecimal sales;
    private Integer orders;
    private Integer units;
    private BigDecimal acos;
    private BigDecimal roas;
    private BigDecimal cr;
    private BigDecimal ctr;
    private BigDecimal cpc;
}
