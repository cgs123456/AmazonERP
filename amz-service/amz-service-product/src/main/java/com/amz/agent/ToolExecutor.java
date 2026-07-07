package com.amz.agent;

import com.google.gson.Gson;
import com.amz.agent.dto.FunctionCall;
import com.amz.context.UserContext;
import com.amz.model.dto.BuyDto;
import com.amz.model.pojo.Product;
import com.amz.model.vo.CouponVo;
import com.amz.model.vo.ProductVo;
import com.amz.result.Result;
import com.amz.service.CartService;
import com.amz.service.CouponService;
import com.amz.service.ProductService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 工具调度器：根据 FunctionCall 调用对应微服务内部接口并返回 JSON 字符串结果。
 *
 * 5 个工具实现：
 * 1. search_products(keyword)        → ProductService.searchProducts
 * 2. get_product_detail(productId)   → ProductService.getProduct
 * 3. add_to_cart(productId, attrs)   → CartService.addToCart
 * 4. create_order(productId, attrs)  → ProductService.buyProduct(BuyDto)（走 Redis Lua + MQ 完整链路）
 * 5. check_coupons()                 → CouponService.getCouponsByUserId
 *
 * 返回值约定：所有工具返回 JSON 字符串 {"ok":true/false,"data":...,"message":"..."}
 * LLM 拿到此结果后判断是否需要继续调用工具或给出最终回复。
 */
@Component
@Slf4j
public class ToolExecutor {

    @Autowired
    private ProductService productService;

    @Autowired
    private CartService cartService;

    @Autowired
    private CouponService couponService;

    private final Gson gson = new Gson();

    /**
     * 执行函数调用
     * @param call LLM 输出的 FunctionCall
     * @return 工具执行结果的 JSON 字符串（注入到对话历史作为 tool 角色消息）
     */
    public String execute(FunctionCall call) {
        if (call == null || call.getName() == null) {
            return fail("无效的工具调用");
        }
        String name = call.getName().trim();
        Map<String, Object> args = call.getArguments() == null ? Map.of() : call.getArguments();
        try {
            switch (name) {
                case "search_products":
                    return searchProducts(args);
                case "get_product_detail":
                    return getProductDetail(args);
                case "add_to_cart":
                    return addToCart(args);
                case "create_order":
                    return createOrder(args);
                case "check_coupons":
                    return checkCoupons();
                default:
                    return fail("未知工具：" + name);
            }
        } catch (Exception e) {
            log.error("执行工具 {} 异常", name, e);
            return fail("工具执行异常：" + e.getMessage());
        }
    }

    // ============ 工具 1：搜索商品 ============
    private String searchProducts(Map<String, Object> args) {
        String keyword = args.get("keyword") == null ? "" : args.get("keyword").toString();
        Result<List<Product>> result = productService.searchProducts(keyword);
        if (result.getCode() == 200) {
            List<Product> list = result.getData();
            // 只返回必要字段，避免上下文过长
            return ok("搜索到 " + list.size() + " 个商品", list);
        }
        return fail(result.getMessage());
    }

    // ============ 工具 2：商品详情 ============
    private String getProductDetail(Map<String, Object> args) {
        Integer productId = toInt(args.get("productId"));
        if (productId == null) {
            return fail("productId 参数缺失或非整数");
        }
        Result<ProductVo> result = productService.getProduct(productId);
        if (result.getCode() == 200) {
            return ok("商品详情", result.getData());
        }
        return fail(result.getMessage());
    }

    // ============ 工具 3：加入购物车 ============
    private String addToCart(Map<String, Object> args) {
        Integer productId = toInt(args.get("productId"));
        if (productId == null) {
            return fail("productId 参数缺失或非整数");
        }
        Integer userId = UserContext.getUserId();
        if (userId == null) {
            return fail("用户未登录");
        }
        // Agent 加入购物车固定数量为 1（attributes 暂不入库，仅作为上下文信息）
        Result<Void> result = cartService.addToCart(productId, 1);
        if (result.getCode() == 200) {
            return ok("已加入购物车", null);
        }
        return fail(result.getMessage());
    }

    // ============ 工具 4：立即下单（走完整库存预扣链路） ============
    private String createOrder(Map<String, Object> args) {
        Integer productId = toInt(args.get("productId"));
        if (productId == null) {
            return fail("productId 参数缺失或非整数");
        }
        Integer userId = UserContext.getUserId();
        if (userId == null) {
            return fail("用户未登录");
        }
        BuyDto buyDto = new BuyDto();
        buyDto.setProductId(productId);
        buyDto.setUserId(userId);
        // attributes 暂不参与下单逻辑（仅作为对话上下文）
        Result<Void> result = productService.buyProduct(buyDto);
        if (result.getCode() == 200) {
            return ok("下单成功，正在扣减库存并创建订单", null);
        }
        return fail(result.getMessage());
    }

    // ============ 工具 5：查询优惠券 ============
    private String checkCoupons() {
        Result<List<CouponVo>> result = couponService.getCouponsByUserId();
        if (result.getCode() == 200) {
            return ok("查询到 " + result.getData().size() + " 张优惠券", result.getData());
        }
        return fail(result.getMessage());
    }

    // ============ 工具方法 ============
    private Integer toInt(Object obj) {
        if (obj == null) return null;
        try {
            return Integer.parseInt(obj.toString().trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String ok(String message, Object data) {
        return gson.toJson(Map.of("ok", true, "message", message == null ? "" : message, "data", data == null ? "" : data));
    }

    private String fail(String message) {
        return gson.toJson(Map.of("ok", false, "message", message == null ? "未知错误" : message));
    }
}
