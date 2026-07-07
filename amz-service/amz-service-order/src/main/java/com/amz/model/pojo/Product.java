package com.amz.model.pojo;

import lombok.Data;

import java.io.Serializable;

@Data
public class Product implements Serializable {
    private static final long serialVersionUID = 1L;

    /**
     * 主键
     */
    private Integer id;

    /**
     * 商品名称
     */
    private String name;

    /**
     * 商品价格
     */
    private Double price;

    /**
     * 商品图片
     */
    private String image;

    /**
     * 发布时间
     */
    private String time;

    /**
     * 销量
     */
    private Integer sales;

    /**
     * 用户id
     */
    private Integer userId;

    /**
     * 店铺id
     */
    private Integer shopId;

    /**
     * 库存
     */
    private Integer stock;
}
