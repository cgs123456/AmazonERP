package com.amz.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;

/**
 * FBA 费率表实体
 */
@Data
@TableName("amz_fba_fee_table")
public class FbaFeeTable {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    @TableField("size_tier")
    private String sizeTier;

    @TableField("weight_g")
    private Integer weightG;

    private String region;

    @TableField("fulfillment_fee")
    private BigDecimal fulfillmentFee;

    @TableField("storage_fee_per_month")
    private BigDecimal storageFeePerMonth;
}
