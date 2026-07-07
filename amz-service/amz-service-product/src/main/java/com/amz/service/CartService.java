package com.amz.service;

import com.amz.model.pojo.Cart;
import com.amz.result.Result;

import java.util.List;

public interface CartService {
    /**
     * 添加商品到购物车
     */
    Result<Void> addToCart(Integer productId, Integer quantity);

    /**
     * 获取用户购物车列表
     */
    Result<List<Cart>> getCartList();

    /**
     * 更新购物车商品数量
     */
    Result<Void> updateCartQuantity(Integer cartId, Integer quantity);

    /**
     * 删除购物车商品
     */
    Result<Void> removeFromCart(Integer cartId);

    /**
     * 清空购物车
     */
    Result<Void> clearCart();
}
