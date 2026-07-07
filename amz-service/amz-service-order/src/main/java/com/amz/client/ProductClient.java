package com.amz.client;

import com.amz.model.pojo.Product;
import com.amz.result.Result;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;

@Component
@FeignClient("redbook-service-product")
public interface ProductClient {

    @GetMapping("/product/getProduct/{productId}")
    Result<Product> getProductById(@PathVariable Integer productId);

    @PutMapping("/product/updateProduct")
    Result<Void> updateProduct(@RequestBody Product product);
}
