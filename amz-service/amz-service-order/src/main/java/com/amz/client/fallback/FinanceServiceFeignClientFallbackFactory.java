package com.amz.client.fallback;

import com.amz.client.FinanceServiceFeignClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * FinanceServiceFeignClient 降级工厂。
 * <p>
 * 当 amz-service-finance 不可用或调用超时时，返回静默降级代理，
 * 避免订单服务因财务服务故障而整体不可用（凭证可后续按订单号补生成）。
 */
@Slf4j
@Component
public class FinanceServiceFeignClientFallbackFactory implements FallbackFactory<FinanceServiceFeignClient> {

    @Override
    public FinanceServiceFeignClient create(Throwable cause) {
        log.warn("Feign call to amz-service-finance (order) degraded: cause={}", cause.getMessage());
        return new FinanceServiceFeignClient() {
            @Override
            public void generateOrderVoucher(Long shopId, String orderNo, BigDecimal amount, String currency) {
                // 静默降级：凭证可后续按订单号补生成，不阻断订单落库主流程
                log.warn("generateOrderVoucher 降级跳过：shopId={} orderNo={} amount={} currency={}",
                        shopId, orderNo, amount, currency);
            }
        };
    }
}
