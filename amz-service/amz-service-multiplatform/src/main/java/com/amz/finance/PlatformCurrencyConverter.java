package com.amz.finance;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * 多币种汇率转换器（多平台对接模块用）。
 * <p>
 * 委托 {@link GlobalExchangeRateService}（amz-common 共享汇率服务）完成实时汇率换算，
 * 自身不再维护静态汇率表。保留 {@link #toCny(BigDecimal, String)} 公共接口以兼容现有调用方与单测。
 */
@Slf4j
@Component
public class PlatformCurrencyConverter {

    private final GlobalExchangeRateService exchangeRateService;

    public PlatformCurrencyConverter(GlobalExchangeRateService exchangeRateService) {
        this.exchangeRateService = exchangeRateService;
    }

    /**
     * 将原币种金额折算为人民币。
     *
     * @param originalAmount 原币种金额
     * @param currency       币种代码
     * @return 人民币金额（保留 2 位）
     */
    public BigDecimal toCny(BigDecimal originalAmount, String currency) {
        return exchangeRateService.toCny(originalAmount, currency);
    }
}
