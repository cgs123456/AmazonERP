package com.amz.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("amz_profit_snapshot")
public class ProfitSnapshot {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long shopId;
    private String sku;
    private String asin;
    private LocalDateTime statTime;
    private BigDecimal salesAmount;
    private Integer salesQuantity;
    private BigDecimal productCost;
    private BigDecimal fbaFees;
    private BigDecimal referralFee;
    private BigDecimal advertisingCost;
    private BigDecimal storageFee;
    private BigDecimal headhaulCost;
    private BigDecimal vatCost;
    private BigDecimal refundCost;
    private BigDecimal otherCost;
    private BigDecimal grossProfit;
    private BigDecimal netProfit;
    private BigDecimal margin;
    private String dataSource;
    private LocalDateTime createTime;
}
