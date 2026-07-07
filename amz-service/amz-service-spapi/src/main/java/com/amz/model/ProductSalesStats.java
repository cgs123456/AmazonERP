package com.amz.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;

/**
 * 商品销量统计实体。
 * <p>
 * 对应 amz_spapi.amz_product_sales_stats 表，存储按 SKU 维度的多窗口销量汇总，
 * 供库存健康度（DOS）计算使用。唯一键：shop_id + sku + stat_date。
 */
@Data
@TableName("amz_product_sales_stats")
public class ProductSalesStats {

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
     * 统计日期。
     */
    @TableField("stat_date")
    private LocalDate statDate;

    /**
     * 当日销量。
     */
    @TableField("qty_1_day")
    private Integer qty1Day;

    /**
     * 7 天累计销量。
     */
    @TableField("qty_7_days")
    private Integer qty7Days;

    /**
     * 30 天累计销量。
     */
    @TableField("qty_30_days")
    private Integer qty30Days;

    /**
     * 90 天累计销量。
     */
    @TableField("qty_90_days")
    private Integer qty90Days;
}
