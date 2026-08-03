package com.amz.finance;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * 多币种核算器。
 * <p>
 * 启动时从 yml 读取固定汇率作为兜底（{@code kingdee.exchange-rates}），
 * 之后通过 {@link #refreshRates()} 定时（每小时）从 exchangerate.host
 * （欧洲央行数据源，免费免 key）拉取最新汇率刷新内存缓存。
 * <p>
 * 拉取失败时降级沿用上次缓存（首次失败沿用 yml 兜底汇率），不阻断业务。
 * <p>
 * 汇率表语义：1 单位原币 = rate CNY。
 */
@Slf4j
@Component
public class CurrencyConverter {

    /** exchangerate.host 免费 API（base=USD），无需 key */
    @Value("${exchangerate.api-url:https://api.exchangerate.host/latest?base=USD}")
    private String exchangeRateApi;

    /** 兜底汇率（yml 配置），首次拉取失败时使用 */
    private final Map<String, BigDecimal> fallbackRates;

    /** 当前生效汇率缓存（volatile 引用 swap 保证可见性与原子性） */
    private volatile Map<String, BigDecimal> exchangeRates;

    private final RestTemplate restTemplate = new RestTemplate();

    public CurrencyConverter(@Value("#{${kingdee.exchange-rates}}") Map<String, BigDecimal> rates) {
        this.fallbackRates = rates != null ? new HashMap<>(rates) : new HashMap<>();
        this.exchangeRates = new HashMap<>(this.fallbackRates);
        log.info("多币种核算器初始化：兜底币种 {}", this.fallbackRates.keySet());
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

    /**
     * 每小时从 exchangerate.host 拉取最新汇率刷新缓存。
     * <p>
     * API 返回 base=USD 的交叉汇率，需换算为「1 原币 = X CNY」：
     * rate(currency→CNY) = rates["CNY"] / rates[currency]。
     * <p>
     * 拉取失败降级：保留现有缓存不变（首次失败保留 yml 兜底），
     * 不抛异常、不阻断业务。
     */
    @Scheduled(fixedRate = 60 * 60 * 1000L)
    public void refreshRates() {
        try {
            Map<String, Object> resp = fetchUsdRates();
            if (resp == null) {
                log.warn("exchangerate.host 返回空响应，保留现有汇率缓存");
                return;
            }
            Object ratesObj = resp.get("rates");
            if (!(ratesObj instanceof Map)) {
                log.warn("exchangerate.host 返回 rates 字段格式异常，保留现有汇率缓存");
                return;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> usdRates = (Map<String, Object>) ratesObj;
            Object cnyObj = usdRates.get("CNY");
            if (cnyObj == null) {
                log.warn("exchangerate.host 返回缺 CNY 汇率，保留现有汇率缓存");
                return;
            }
            BigDecimal cnyPerUsd = toBigDecimal(cnyObj);
            if (cnyPerUsd == null || cnyPerUsd.signum() <= 0) {
                log.warn("exchangerate.host CNY 汇率非法：{}，保留现有汇率缓存", cnyPerUsd);
                return;
            }

            Map<String, BigDecimal> refreshed = new HashMap<>();
            // USD → CNY 直接用 CNY 汇率
            refreshed.put("USD", cnyPerUsd.setScale(6, RoundingMode.HALF_UP));
            // 其他币种：rate(currency→CNY) = cnyPerUsd / usdRates[currency]
            for (Map.Entry<String, Object> entry : usdRates.entrySet()) {
                String ccy = entry.getKey().toUpperCase();
                if ("USD".equals(ccy) || "CNY".equals(ccy)) {
                    continue;
                }
                BigDecimal usdToCcy = toBigDecimal(entry.getValue());
                if (usdToCcy == null || usdToCcy.signum() <= 0) {
                    continue;
                }
                BigDecimal cnyPerCcy = cnyPerUsd.divide(usdToCcy, 6, RoundingMode.HALF_UP);
                refreshed.put(ccy, cnyPerCcy);
            }
            // 合并兜底：拉取结果未覆盖的币种保留旧值，避免 API 偶尔丢币种导致缺失
            for (Map.Entry<String, BigDecimal> e : exchangeRates.entrySet()) {
                refreshed.putIfAbsent(e.getKey(), e.getValue());
            }
            exchangeRates = Collections.unmodifiableMap(refreshed);
            log.info("汇率缓存刷新成功：共 {} 个币种（来源 exchangerate.host）", refreshed.size());
        } catch (Exception e) {
            log.warn("exchangerate.host 汇率拉取失败，保留现有汇率缓存：{}", e.getMessage());
        }
    }

    /**
     * 调用 exchangerate.host 拉取 base=USD 的汇率响应。
     * <p>
     * 抽成独立方法便于单元测试通过子类 override 注入 fake 响应，
     * 避免真实网络调用。
     *
     * @return API 响应（含 rates 字段），失败抛异常由调用方降级
     */
    Map<String, Object> fetchUsdRates() {
        @SuppressWarnings("unchecked")
        Map<String, Object> resp = restTemplate.getForObject(exchangeRateApi, Map.class);
        return resp;
    }

    private BigDecimal toBigDecimal(Object o) {
        if (o == null) {
            return null;
        }
        if (o instanceof BigDecimal) {
            return (BigDecimal) o;
        }
        if (o instanceof Number) {
            return new BigDecimal(((Number) o).toString());
        }
        try {
            return new BigDecimal(o.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}