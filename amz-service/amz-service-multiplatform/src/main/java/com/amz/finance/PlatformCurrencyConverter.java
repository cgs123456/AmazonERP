package com.amz.finance;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;

/**
 * 多币种汇率转换器（多平台对接模块用）。
 * <p>
 * 汇率从 application.yml 的 platform.exchange-rates 注入，
 * key 为币种代码（USD/EUR/GBP/JPY），value 为兑 CNY 的汇率。
 */
@Component
public class PlatformCurrencyConverter {

    /**
     * 汇率表：币种代码 → CNY 汇率。
     */
    @Value("#{${platform.exchange-rates}}")
    private Map<String, BigDecimal> rates;

    /**
     * 将原币种金额折算为人民币。
     *
     * @param originalAmount 原币种金额
     * @param currency       币种代码
     * @return 人民币金额（保留 2 位）
     */
    public BigDecimal toCny(BigDecimal originalAmount, String currency) {
        if (originalAmount == null || currency == null) {
            return BigDecimal.ZERO;
        }
        BigDecimal rate = rates.get(currency);
        if (rate == null) {
            // 默认按 1:1 兜底（生产应抛异常并告警）
            return originalAmount.setScale(2, RoundingMode.HALF_UP);
        }
        return originalAmount.multiply(rate).setScale(2, RoundingMode.HALF_UP);
    }
}
