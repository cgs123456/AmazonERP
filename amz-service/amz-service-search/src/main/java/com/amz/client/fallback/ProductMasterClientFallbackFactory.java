package com.amz.client.fallback;

import com.amz.client.ProductMasterClient;
import com.amz.result.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.stereotype.Component;

/**
 * 商品主数据读取降级。
 * <p>
 * 返回失败而不是空分页：重建服务必须能区分「product 服务不可用」与「商品表为空」。
 * 后者是正常状态（新部署），前者若被当成空结果，重建会把已有索引清空——
 * 用一次服务抖动换掉全部搜索数据，不可接受。
 */
@Slf4j
@Component
public class ProductMasterClientFallbackFactory implements FallbackFactory<ProductMasterClient> {

    @Override
    public ProductMasterClient create(Throwable cause) {
        log.warn("Feign call to amz-service-product (product master) degraded", cause);
        return (afterId, limit) ->
                Result.failure("product service degraded: " + cause.getMessage());
    }
}
