package com.amz.model.vo;

import com.amz.model.pojo.CustomAttribute;
import com.amz.model.pojo.Product;
import com.amz.model.pojo.Shop;
import lombok.Data;

import java.util.List;

@Data
public class ProductVo {

    private Product product;

    /**
     * 店铺
     */
    private Shop shop;

    /**
     * 商品属性
     */
    private List<CustomAttribute> customAttributes;
}
