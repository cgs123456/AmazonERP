package com.amz.controller;

import com.amz.context.UserContext;
import com.amz.model.dto.BuyDto;
import com.amz.model.dto.ProductDto;
import com.amz.model.pojo.Product;
import com.amz.model.vo.ProductVo;
import com.amz.result.Result;
import com.amz.service.ProductService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/product")
public class ProductController {

    @Autowired
    private ProductService productService;

    @GetMapping("/getProductList")
    public Result<List<Product>> getProductList() {
        return productService.getProductList();
    }

    @GetMapping("/getProduct/{productId}")
    public Result<ProductVo> getProduct(@PathVariable Integer productId) {
        return productService.getProduct(productId);
    }

    @GetMapping("/getProductsByShop/{productId}")
    public Result<List<Product>> getProductsByShop(@PathVariable Integer productId) {
        return productService.getProductByShop(productId);
    }

    @PostMapping("/postProduct")
    public Result<Void> postProduct(@RequestBody ProductDto productDto) {
        return productService.postProduct(productDto);
    }

    @PutMapping("/updateProduct")
    public Result<Void> updateProduct(@RequestBody ProductDto productDto) {
        return productService.updateProduct(productDto);
    }

    @PostMapping("/buyProduct")
    public Result<Void> buyProduct(@RequestBody BuyDto buyDto) {
        if (buyDto.getProductId() == null) {
            return Result.failure("商品ID不能为空");
        }
        return productService.buyProduct(buyDto);
    }

    @PostMapping("/agent/buyProduct")
    public Result<String> agentBuyProduct(@RequestBody String message) {
        return productService.buyProduct(message);
    }

    /**
     * 购物 Agent 多轮对话端点（支持 conversationId 维持上下文）
     * 请求体示例：{"conversationId":"abc123","message":"帮我买那个红色的"}
     */
    @PostMapping("/agent/chat")
    public Result<String> agentChat(@RequestBody AgentChatRequest request) {
        if (request.getMessage() == null || request.getMessage().trim().isEmpty()) {
            return Result.failure("message 不能为空");
        }
        return productService.agentChat(request.getConversationId(), request.getMessage());
    }

    public static class AgentChatRequest {
        private String conversationId;
        private String message;

        public String getConversationId() { return conversationId; }
        public void setConversationId(String conversationId) { this.conversationId = conversationId; }
        public String getMessage() { return message; }
        public void setMessage(String message) { this.message = message; }
    }

    @GetMapping("/helper/buyProduct")
    public Result<Void> helperBuyProduct(@RequestParam Integer productId) {
        if (productId == null) {
            return Result.failure("商品ID不能为空");
        }
        BuyDto buyDto = new BuyDto();
        buyDto.setProductId(productId);
        buyDto.setUserId(UserContext.getUserId());
        return productService.buyProduct(buyDto);
    }
}
