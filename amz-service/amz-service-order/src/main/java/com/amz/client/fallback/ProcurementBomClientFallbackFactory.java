package com.amz.client.fallback;

import com.amz.client.ProcurementBomClient;
import com.amz.result.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * BOM 展开降级工厂。
 * <p>
 * 返回失败：展开失败时继续履约 = 按组合 SKU 去扣一个不存在的库存行，
 * 比直接失败隐蔽得多。调用方（配货编排）拿到失败即中止，订单保持未配货。
 */
@Slf4j
@Component
public class ProcurementBomClientFallbackFactory implements FallbackFactory<ProcurementBomClient> {

    @Override
    public ProcurementBomClient create(Throwable cause) {
        log.warn("Feign call to amz-service-procurement (bom) degraded", cause);
        final String reason = "procurement service degraded: " + cause.getMessage();
        return new ProcurementBomClient() {
            @Override
            public Result<List<Map<String, Object>>> expand(Long shopId, List<Map<String, Object>> requests) {
                return Result.failure(reason);
            }
        };
    }
}
