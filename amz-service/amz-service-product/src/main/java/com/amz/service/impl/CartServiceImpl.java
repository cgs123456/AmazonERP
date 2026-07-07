package com.amz.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.amz.context.UserContext;
import com.amz.mapper.CartMapper;
import com.amz.mapper.ProductMapper;
import com.amz.model.pojo.Cart;
import com.amz.model.pojo.Product;
import com.amz.result.Result;
import com.amz.service.CartService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Service
@Slf4j
public class CartServiceImpl implements CartService {

    @Autowired
    private CartMapper cartMapper;

    @Autowired
    private ProductMapper productMapper;

    @Override
    @Transactional
    public Result<Void> addToCart(Integer productId, Integer quantity) {
        Integer userId = UserContext.getUserId();
        if (userId == null) {
            return Result.failure("用户未登录");
        }

        // 检查商品是否存在
        Product product = productMapper.selectById(productId);
        if (product == null) {
            return Result.failure("商品不存在");
        }

        // 检查库存
        if (product.getStock() < quantity) {
            return Result.failure("库存不足");
        }

        // 检查购物车中是否已存在该商品
        LambdaQueryWrapper<Cart> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(Cart::getUserId, userId)
                   .eq(Cart::getProductId, productId);
        Cart existingCart = cartMapper.selectOne(queryWrapper);

        if (existingCart != null) {
            // 已存在，更新数量
            existingCart.setQuantity(existingCart.getQuantity() + quantity);
            existingCart.setUpdateTime(LocalDateTime.now());
            cartMapper.updateById(existingCart);
        } else {
            // 不存在，新增购物车记录
            Cart cart = new Cart();
            cart.setUserId(userId);
            cart.setProductId(productId);
            cart.setProductName(product.getName());
            cart.setProductImage(product.getImage());
            cart.setProductPrice(product.getPrice());
            cart.setQuantity(quantity);
            cart.setCreateTime(LocalDateTime.now());
            cart.setUpdateTime(LocalDateTime.now());
            cartMapper.insert(cart);
        }

        return Result.success(null);
    }

    @Override
    public Result<List<Cart>> getCartList() {
        Integer userId = UserContext.getUserId();
        if (userId == null) {
            return Result.failure("用户未登录");
        }

        LambdaQueryWrapper<Cart> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(Cart::getUserId, userId)
                   .orderByDesc(Cart::getCreateTime);
        List<Cart> cartList = cartMapper.selectList(queryWrapper);

        return Result.success(cartList);
    }

    @Override
    @Transactional
    public Result<Void> updateCartQuantity(Integer cartId, Integer quantity) {
        Integer userId = UserContext.getUserId();
        if (userId == null) {
            return Result.failure("用户未登录");
        }

        Cart cart = cartMapper.selectById(cartId);
        if (cart == null || !cart.getUserId().equals(userId)) {
            return Result.failure("购物车记录不存在");
        }

        // 检查库存
        Product product = productMapper.selectById(cart.getProductId());
        if (product == null || product.getStock() < quantity) {
            return Result.failure("库存不足");
        }

        cart.setQuantity(quantity);
        cart.setUpdateTime(LocalDateTime.now());
        cartMapper.updateById(cart);

        return Result.success(null);
    }

    @Override
    @Transactional
    public Result<Void> removeFromCart(Integer cartId) {
        Integer userId = UserContext.getUserId();
        if (userId == null) {
            return Result.failure("用户未登录");
        }

        Cart cart = cartMapper.selectById(cartId);
        if (cart == null || !cart.getUserId().equals(userId)) {
            return Result.failure("购物车记录不存在");
        }

        cartMapper.deleteById(cartId);
        return Result.success(null);
    }

    @Override
    @Transactional
    public Result<Void> clearCart() {
        Integer userId = UserContext.getUserId();
        if (userId == null) {
            return Result.failure("用户未登录");
        }

        LambdaQueryWrapper<Cart> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(Cart::getUserId, userId);
        cartMapper.delete(queryWrapper);

        return Result.success(null);
    }
}
