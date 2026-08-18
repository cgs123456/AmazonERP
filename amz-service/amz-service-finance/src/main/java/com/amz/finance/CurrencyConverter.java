package com.amz.finance;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * 多币种核算器（finance 模块门面）。
 * <p>
 * <b>已重构为委托实现</b>：汇率的拉取、缓存、定时刷新与降级逻辑统一收敛到
 * amz-common 的 {@link GlobalExchangeRateService}，本类只保留 finance 模块的调用门面，
 * 使 {@code FinanceServiceImpl} 等既有调用点无需改动。
 * <p>
 * 重构动因（原实现的问题）：
 * <ul>
 *   <li>与 product / multiplatform 模块存在三套重复汇率实现，数据源与刷新周期各不相同，
 *       同一笔业务在不同模块折算可能得到不同 CNY 金额</li>
 *   <li>自建裸 {@code new RestTemplate()}，无超时 / 无重试 / 无熔断 / 无指标</li>
 *   <li>各自 {@code @Scheduled} 刷新，同一进程内重复拉取同一外部 API</li>
 * </ul>
 * 现状：单一数据源、单一刷新任务、出站走
 * {@code com.amz.http.ResilientHttpClient} 弹性通道。
 * <p>
 * 汇率语义不变：1 单位原币 = rate CNY。
 */
@Slf4j
@Component
public class CurrencyConverter {

    private final GlobalExchangeRateService delegate;

    public CurrencyConverter(GlobalExchangeRateService delegate) {
        this.delegate = delegate;
        log.info("多币种核算器初始化：已委托 amz-common GlobalExchangeRateService（单一汇率真相源）");
    }

    /**
     * 将原币金额转换为 CNY 本位币。
     *
     * @param originalAmount 原币金额
     * @param currency       原币币种
     * @return CNY 金额（2 位小数）；入参为 null 时返回 0
     */
    public BigDecimal convertToCny(BigDecimal originalAmount, String currency) {
        return delegate.toCny(originalAmount, currency);
    }

    /**
     * 获取某币种当前汇率（1 原币 = X CNY）。CNY 恒为 1，未知币种降级为 1。
     */
    public BigDecimal getRate(String currency) {
        return delegate.getRate(currency);
    }
}
