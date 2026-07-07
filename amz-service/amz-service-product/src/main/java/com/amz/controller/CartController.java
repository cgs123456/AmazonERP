package com.amz.controller;

import com.amz.model.pojo.Cart;
import com.amz.result.Result;
import com.amz.service.CartService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/cart")
public class CartController {

    @Autowired
    private CartService cartService;

    /**
     * 添加商品到购物车
     */
    @PostMapping("/addToCart")
    public Result<Void> addToCart(@RequestParam Integer productId,
                                  @RequestParam(defaultValue = "1") Integer quantity) {
        if (productId == null) {
            return Result.failure("商品ID不能为空");
        }
        return cartService.addToCart(productId, quantity);
    }

    /**
     * 获取用户购物车列表
     */
    @GetMapping("/getCartList")
    public Result<List<Cart>> getCartList() {
        return cartService.getCartList();
    }

    /**
     * 更新购物车商品数量
     */
    @PutMapping("/updateQuantity")
    public Result<Void> updateQuantity(@RequestParam Integer cartId,
                                       @RequestParam Integer quantity) {
        if (cartId == null || quantity == null || quantity < 1) {
            return Result.failure("参数不合法");
        }
        return cartService.updateCartQuantity(cartId, quantity);
    }

    /**
     * 删除购物车商品
     */
    @DeleteMapping("/remove/{cartId}")
    public Result<Void> removeFromCart(@PathVariable Integer cartId) {
        return cartService.removeFromCart(cartId);
    }

    /**
     * 清空购物车
     */
    @DeleteMapping("/clear")
    public Result<Void> clearCart() {
        return cartService.clearCart();
    }
}
