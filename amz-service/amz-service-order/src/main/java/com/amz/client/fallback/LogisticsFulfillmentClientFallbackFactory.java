package com.amz.client.fallback;

import com.amz.client.LogisticsFulfillmentClient;
import com.amz.result.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 履约支撑降级工厂。
 * <p>
 * 返回失败：配货是「扣库存」的有副作用动作，降级绝不能伪装成功——
 * 调用方拿到失败后订单保持未配货，可安全重试（logistics 侧整体事务，
 * 失败即回滚，不存在「扣了库存但返回失败」的中间态）。
 */
@Slf4j
@Component
public class LogisticsFulfillmentClientFallbackFactory implements FallbackFactory<LogisticsFulfillmentClient> {

    @Override
    public LogisticsFulfillmentClient create(Throwable cause) {
        log.warn("Feign call to amz-service-logistics (fulfillment) degraded", cause);
        final String reason = "logistics service degraded: " + cause.getMessage();
        return new LogisticsFulfillmentClient() {
            @Override
            public Result<List<Map<String, Object>>> checkStock(Map<String, Object> request) {
                return Result.failure(reason);
            }

            @Override
            public Result<Map<String, Object>> executeOutbound(Map<String, Object> request) {
                return Result.failure(reason);
            }
        };
    }
}
