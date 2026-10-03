package com.amz.model.vo;

import com.amz.model.pojo.CustomAttribute;
import com.amz.model.pojo.Product;
import lombok.Data;

import java.util.List;

@Data
public class ProductVo {

    private Product product;

    /**
     * 商品属性
     */
    private List<CustomAttribute> customAttributes;
}
