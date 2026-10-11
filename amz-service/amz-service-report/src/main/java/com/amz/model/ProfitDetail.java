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
    /** 退款金额（正数表示损失）。V2 新增：此前无此列，快照的 refundCost 因此恒为 0。 */
    private BigDecimal refundCost;
    /** 退款件数（用于冲减 COGS）。V2 新增。 */
    private Integer refundedUnits;
    private BigDecimal grossProfit;
    private BigDecimal netProfit;
    private BigDecimal margin;
    private String currency;
    private BigDecimal exchangeRate;
}
