package com.amz.model.dto;

import com.amz.model.pojo.Product;
import com.amz.model.pojo.ProductAttribute;
import lombok.Data;
import lombok.EqualsAndHashCode;

@EqualsAndHashCode(callSuper = true)
@Data
public class ProductDto extends Product {

    /**
     * 商品属性
     */
    private ProductAttribute productAttribute;
}
