package com.amz.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 采购成本表实体
 */
@Data
@TableName("amz_product_cost")
public class ProductCost {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    @TableField("shop_id")
    private Long shopId;

    private String sku;

    @TableField("unit_cost")
    private BigDecimal unitCost;

    @TableField("shipping_cost")
    private BigDecimal shippingCost;

    @TableField("customs_cost")
    private BigDecimal customsCost;

    @TableField("lead_time_days")
    private Integer leadTimeDays;

    @TableField("update_time")
    private LocalDateTime updateTime;
}
