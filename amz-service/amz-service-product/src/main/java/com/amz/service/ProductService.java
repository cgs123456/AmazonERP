package com.amz.service;

import com.amz.model.dto.ProductDto;
import com.amz.model.pojo.Product;
import com.amz.model.vo.ProductVo;
import com.amz.result.Result;

import java.util.List;

public interface ProductService {
    Result<List<Product>> getProductList();

    Result<ProductVo> getProduct(Integer productId);

    Result<List<Product>> getProductByShop(Integer productId);

    Result<Void> postProduct(ProductDto productDto);

    Result<Void> updateProduct(ProductDto productDto);

    /**
     * 按关键词搜索商品（名称/描述/品牌模糊匹配）
     */
    Result<List<Product>> searchProducts(String keyword);

    Product selectById(Integer productId);

    void updateById(Product product);
}