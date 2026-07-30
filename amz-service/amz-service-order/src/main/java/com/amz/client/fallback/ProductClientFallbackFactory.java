package com.amz.client.fallback;

import com.amz.client.ProductClient;
import com.amz.model.pojo.Product;
import com.amz.result.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class ProductClientFallbackFactory implements FallbackFactory<ProductClient> {

    @Override
    public ProductClient create(Throwable cause) {
        log.warn("Feign call to amz-service-product (order) degraded: cause={}", cause.getMessage());
        return new ProductClient() {
            @Override
            public Result<Product> getProductById(Integer productId) {
                return Result.failure("product service degraded: " + cause.getMessage());
            }

            @Override
            public Result<Void> updateProduct(Product product) {
                return Result.failure("product service degraded: " + cause.getMessage());
            }
        };
    }
}