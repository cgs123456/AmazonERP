package com.amz.service;

import com.amz.model.dto.ProductDto;
import com.amz.model.pojo.Product;
import com.amz.model.vo.ProductVo;
import com.amz.result.PageRequest;
import com.amz.result.Result;

import java.util.List;

public interface ProductService {
    Result<List<Product>> getProductList();

    Result<ProductVo> getProduct(Integer productId);

    Result<List<Product>> getProductByShop(Integer productId);

    Result<Void> postProduct(ProductDto productDto);

    Result<Void> updateProduct(ProductDto productDto);

    /**
     * 按关键词搜索商品（名称/描述/品牌模糊匹配）。
     * <p>
     * 原实现两条分支都固定 {@code LIMIT 20}：命中 500 条也只回 20 条，HTTP 200、
     * 结构与「确实只有 20 条」完全一致。改为 keyset 分页后，
     * 截断通过 {@code _page.truncated} / {@code _page.nextCursor} 显式暴露。
     *
     * @param page 分页参数；null 表示首页 + 默认页大小
     */
    Result<List<Product>> searchProducts(String keyword, PageRequest page);

    Product selectById(Integer productId);

    void updateById(Product product);
}
