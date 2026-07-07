package com.amz.model.pojo;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;

@Data
@TableName("rb_product")
public class Product implements Serializable {
    private static final long serialVersionUID = 1L;

    /**
     * 主键
     */
    @TableId(type = IdType.AUTO)
    private Integer id;

    /**
     * 商品名称
     */
    @TableField("name")
    private String name;

    /**
     * 商品类型
     */
    @TableField("type")
    private String type;

    /**
     * 商品描述
     */
    @TableField("description")
    private String description;

    /**
     * 商品品牌
     */
    @TableField("brand")
    private String brand;

    /**
     * 商品价格
     */
    @TableField("price")
    private BigDecimal price;

    /**
     * 商品图片
     */
    @TableField("image")
    private String image;

    /**
     * 发布时间
     */
    @TableField("time")
    private String time;

    /**
     * 销量
     */
    @TableField("sales")
    private Integer sales;

    /**
     * 用户id
     */
    @TableField("user_id")
    private Integer userId;

    /**
     * 店铺id
     */
    @TableField("shop_id")
    private Integer shopId;

    /**
     * 库存
     */
    @TableField("stock")
    private Integer stock;
}
