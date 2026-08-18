package com.amz.service;

import com.amz.finance.GlobalExchangeRateService;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

/**
 * 汇率服务：委托 amz-common 共享 {@link GlobalExchangeRateService} 提供实时汇率换算。
 * <p>
 * 统一收敛原 product 模块独立的 open.er-api.com 调用，复用共享服务的缓存与兜底策略。
 * 失败时降级返回 1（同币种原值），保证业务流程不中断。
 */
@Service
public class ExchangeRateService {

    private final GlobalExchangeRateService globalExchangeRateService;

    public ExchangeRateService(GlobalExchangeRateService globalExchangeRateService) {
        this.globalExchangeRateService = globalExchangeRateService;
    }

    /**
     * 获取 fromCurrency -> toCurrency 的汇率（经 CNY 中转的交叉汇率）。
     *
     * @param fromCurrency 源币种代码（如 USD）
     * @param toCurrency   目标币种代码（如 EUR）
     * @return 汇率；同币种或调用失败返回 BigDecimal.ONE
     */
    public BigDecimal getRate(String fromCurrency, String toCurrency) {
        return globalExchangeRateService.getRate(fromCurrency, toCurrency);
    }
}
