package com.amz.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 销售趋势日报表实体。
 */
@Data
@TableName("amz_sales_daily")
public class SalesDaily implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long shopId;
    private String asin;
    private String sku;
    private LocalDate reportDate;
    private Integer unitsOrdered;
    private Integer unitsRefunded;
    private Integer netUnits;
    private BigDecimal grossSales;
    private BigDecimal refundAmount;
    private BigDecimal netSales;
    private Integer sessions;
    private Integer pageViews;
    private BigDecimal conversionRate;
    private BigDecimal buyBoxPercentage;
    private BigDecimal unitsPerSession;
}
