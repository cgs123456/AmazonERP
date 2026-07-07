package com.amz.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 销售历史（按日）实体。
 * <p>
 * 对应 amz_spapi.amz_sales_history 表，存储 SKU 每日销量与销售额。
 * 唯一键：shop_id + sku + sale_date。
 */
@Data
@TableName("amz_sales_history")
public class SalesHistory {

    /**
     * 主键。
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 店铺 ID。
     */
    @TableField("shop_id")
    private Long shopId;

    /**
     * 卖家 SKU。
     */
    @TableField("sku")
    private String sku;

    /**
     * 销售日期。
     */
    @TableField("sale_date")
    private LocalDate saleDate;

    /**
     * 当日销量。
     */
    @TableField("quantity")
    private Integer quantity;

    /**
     * 当日销售额。
     */
    @TableField("revenue")
    private BigDecimal revenue;
}
