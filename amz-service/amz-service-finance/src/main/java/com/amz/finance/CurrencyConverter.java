package com.amz.finance;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;

/**
 * 多币种核算器。
 * <p>
 * 从 yml 读取汇率配置（生产应接 ECB/FED 实时汇率 API），
 * 将原币金额按汇率转换为本位币（CNY）。
 */
@Slf4j
@Component
public class CurrencyConverter {

    /** 汇率表：currency → rate（1 单位原币 = rate CNY） */
    private final Map<String, BigDecimal> exchangeRates;

    public CurrencyConverter(@Value("#{${kingdee.exchange-rates}}") Map<String, BigDecimal> rates) {
        this.exchangeRates = rates;
        log.info("多币种核算器初始化：支持币种 {}", rates.keySet());
    }

    /**
     * 将原币金额转换为 CNY 本位币。
     *
     * @param originalAmount 原币金额
     * @param currency       原币币种
     * @return CNY 金额（2 位小数）
     */
    public BigDecimal convertToCny(BigDecimal originalAmount, String currency) {
        if (originalAmount == null || currency == null) {
            return BigDecimal.ZERO;
        }
        if ("CNY".equalsIgnoreCase(currency)) {
            return originalAmount.setScale(2, RoundingMode.HALF_UP);
        }
        BigDecimal rate = exchangeRates.get(currency.toUpperCase());
        if (rate == null) {
            log.warn("不支持的币种：{}，按 1:1 处理", currency);
            rate = BigDecimal.ONE;
        }
        return originalAmount.multiply(rate).setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * 获取某币种当前汇率。
     */
    public BigDecimal getRate(String currency) {
        if ("CNY".equalsIgnoreCase(currency)) {
            return BigDecimal.ONE;
        }
        return exchangeRates.getOrDefault(currency.toUpperCase(), BigDecimal.ONE);
    }
}
