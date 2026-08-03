package com.amz.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 店铺经营概览实体。
 */
@Data
@TableName("amz_business_overview")
public class BusinessOverview implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long shopId;
    private LocalDate reportDate;
    private BigDecimal totalSales;
    private Integer totalOrders;
    private Integer totalUnits;
    private BigDecimal avgOrderValue;
    private BigDecimal totalCost;
    private BigDecimal totalAdSpend;
    private BigDecimal totalFees;
    private BigDecimal netProfit;
    private BigDecimal profitMargin;
    private Integer totalRefunds;
    private BigDecimal refundRate;
    private Integer newReviews;
    private BigDecimal avgRating;
    private Integer negativeReviews;
    private Integer customerMessages;
}
