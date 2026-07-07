package com.amz.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Amazon 商品主数据（跨站点 Listing 复制源数据）。
 */
@Data
@TableName("amz_product")
public class AmzProduct implements Serializable {
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("shop_id")
    private Long shopId;

    @TableField("sku")
    private String sku;

    @TableField("asin")
    private String asin;

    @TableField("marketplace_id")
    private String marketplaceId;

    @TableField("title")
    private String title;

    @TableField("description")
    private String description;

    @TableField("brand")
    private String brand;

    @TableField("price")
    private BigDecimal price;

    @TableField("currency")
    private String currency;

    @TableField("category")
    private String category;

    @TableField("size_tier")
    private String sizeTier;

    @TableField("weight_g")
    private Integer weightG;

    @TableField("status")
    private String status;

    @TableField("create_time")
    private LocalDateTime createTime;
}
