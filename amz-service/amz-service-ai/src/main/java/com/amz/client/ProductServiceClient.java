package com.amz.client;

import com.amz.result.Result;
import com.amz.client.fallback.ProductServiceClientFallbackFactory;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.Map;

/**
 * 商品微服务 Feign 客户端。
 * 用于 Agent 工具调用跨站点 Listing 复制、翻译、FBA 费用估算。
 */
@FeignClient(name = "amz-service-product", contextId = "productServiceClient", fallbackFactory = ProductServiceClientFallbackFactory.class)
public interface ProductServiceClient {

    /**
     * 跨站点 Listing 复制。
     */
    @PostMapping("/product/listing/copy")
    Result<Map<String, Object>> copyListing(@RequestBody Map<String, Object> request);

    /**
     * 多语种翻译。
     */
    @PostMapping("/product/translate")
    Result<Map<String, Object>> translate(@RequestBody Map<String, Object> request);

    /**
     * FBA 费用估算。
     */
    @GetMapping("/product/fees/estimate")
    Result<Map<String, Object>> estimateFbaFees(@RequestParam(value = "asin", required = false) String asin,
                                                @RequestParam(value = "price", required = false) Double price,
                                                @RequestParam(value = "weight", required = false) Double weight,
                                                @RequestParam(value = "sizeTier", required = false) String sizeTier);
}
