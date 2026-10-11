package com.amz.client.fallback;

import com.amz.client.SpApiFbaInboundClient;
import com.amz.result.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * FBA 入库计划客户端降级工厂。
 * <p>
 * spapi 不可用时返回失败，调用方据此标记「计划未创建」——
 * <b>不伪造</b>一个本地计划 ID：后续所有入库动作都会指向它，
 * 而 Amazon 侧根本不存在这个对象，等到装箱/贴标才暴露，排查成本极高。
 */
@Slf4j
@Component
public class SpApiFbaInboundClientFallbackFactory implements FallbackFactory<SpApiFbaInboundClient> {

    @Override
    public SpApiFbaInboundClient create(Throwable cause) {
        log.warn("Feign call to amz-service-spapi (fbaInbound) degraded", cause);
        final String reason = "spapi service degraded: " + cause.getMessage();
        return new SpApiFbaInboundClient() {
            @Override
            public Result<Map<String, Object>> execute(String operationId, Map<String, Object> request) {
                log.warn("FBA 入库计划降级：spapi 不可用，operation={}", operationId);
                return Result.failure(reason);
            }
        };
    }
}
