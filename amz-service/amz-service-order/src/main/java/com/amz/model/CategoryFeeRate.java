package com.amz.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 类目佣金率实体
 */
@Data
@TableName("amz_category_fee_rate")
public class CategoryFeeRate {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    @TableField("category_name")
    private String categoryName;

    @TableField("referral_fee_rate")
    private BigDecimal referralFeeRate;
}
