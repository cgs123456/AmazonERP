package com.amz.model;

import com.amz.annotation.FieldPermission;
import com.amz.enums.ConfidentialLevel;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 订单级利润报告实体
 */
@Data
@TableName("amz_profit_report")
public class ProfitReport {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    @TableField("shop_id")
    private Long shopId;

    @TableField("amazon_order_id")
    private String amazonOrderId;

    private String sku;

    @TableField("stat_date")
    private LocalDate statDate;

    private BigDecimal revenue;

    /** 采购成本（COGS），仅 ADMIN/OPERATOR 可见 */
    @TableField("product_cost")
    @FieldPermission(sensitiveLevel = ConfidentialLevel.CONFIDENTIAL)
    private BigDecimal productCost;

    @TableField("fba_fulfillment_fee")
    @FieldPermission(sensitiveLevel = ConfidentialLevel.INTERNAL)
    private BigDecimal fbaFulfillmentFee;

    @TableField("fba_storage_fee")
    private BigDecimal fbaStorageFee;

    @TableField("referral_fee")
    @FieldPermission(sensitiveLevel = ConfidentialLevel.INTERNAL)
    private BigDecimal referralFee;

    @TableField("ad_cost")
    private BigDecimal adCost;

    private BigDecimal vat;

    @TableField("gross_profit")
    @FieldPermission(sensitiveLevel = ConfidentialLevel.CONFIDENTIAL)
    private BigDecimal grossProfit;

    @TableField("net_profit")
    private BigDecimal netProfit;

    @TableField("net_margin")
    private BigDecimal netMargin;

    /**
     * 数据是否完整：缺失采购成本或类目佣金率时为 false，提示该报告仅作估算。
     */
    @TableField("data_complete")
    private Boolean dataComplete;
}
