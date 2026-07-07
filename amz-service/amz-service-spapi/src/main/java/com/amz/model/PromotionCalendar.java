package com.amz.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 促销日历实体。
 * <p>
 * 对应 amz_spapi.amz_promotion_calendar 表，存储 Prime Day / Black Friday 等大促时间窗与销量乘数。
 * 唯一键：promotion_name。
 */
@Data
@TableName("amz_promotion_calendar")
public class PromotionCalendar {

    /**
     * 主键。
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 促销活动名称（如 Prime Day / Black Friday）。
     */
    @TableField("promotion_name")
    private String promotionName;

    /**
     * 促销开始日期。
     */
    @TableField("start_date")
    private LocalDate startDate;

    /**
     * 促销结束日期。
     */
    @TableField("end_date")
    private LocalDate endDate;

    /**
     * 促销销量乘数（2.5-3.0）。
     */
    @TableField("multiplier")
    private BigDecimal multiplier;

    /**
     * 区域：NA/EU/FE/ALL。
     */
    @TableField("region")
    private String region;
}
