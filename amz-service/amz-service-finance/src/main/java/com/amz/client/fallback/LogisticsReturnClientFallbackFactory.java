package com.amz.client.fallback;

import com.amz.client.LogisticsReturnClient;
import com.amz.result.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.stereotype.Component;

/**
 * 退货件数降级工厂。
 * <p>
 * <b>刻意返回失败而非 0</b>：logistics 不可用时若返回 0，财务会把
 * 「查不到退货件数」与「真的没有退货」混为一谈，COGS 因此不会被冲减、利润偏高——
 * 与 {@code ProcurementCostClientFallbackFactory} 同一条纪律。
 * 调用方（{@code SkuProfitServiceImpl}）拿到失败后不冲减 COGS，并在 dataNotes 里说明。
 */
@Slf4j
@Component
public class LogisticsReturnClientFallbackFactory implements FallbackFactory<LogisticsReturnClient> {

    @Override
    public LogisticsReturnClient create(Throwable cause) {
        log.warn("Feign call to amz-service-logistics (returns) degraded", cause);
        final String reason = "logistics service degraded: " + cause.getMessage();
        return new LogisticsReturnClient() {
            @Override
            public Result<Integer> returnedGoodUnits(Long shopId, String sku) {
                return Result.failure(reason);
            }
        };
    }
}
