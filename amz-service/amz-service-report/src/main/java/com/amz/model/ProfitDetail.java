package com.amz.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 利润核算明细实体。
 */
@Data
@TableName("amz_profit_detail")
public class ProfitDetail implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long shopId;
    private String amazonOrderId;
    private String asin;
    private String sku;
    private LocalDate reportDate;
    private BigDecimal productSales;
    private BigDecimal shippingCredits;
    private BigDecimal promotionalRebates;
    private BigDecimal productCost;
    private BigDecimal fbaFees;
    private BigDecimal referralFee;
    private BigDecimal variableClosingFee;
    private BigDecimal inboundFreight;
    private BigDecimal inboundDuty;
    private BigDecimal storageFee;
    private BigDecimal advertisingCost;
    private BigDecimal vatTax;
    private BigDecimal otherFees;
    private BigDecimal grossProfit;
    private BigDecimal netProfit;
    private BigDecimal margin;
    private String currency;
    private BigDecimal exchangeRate;
}
