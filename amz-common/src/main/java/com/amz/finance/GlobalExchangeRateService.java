package com.amz.finance;

import com.amz.http.ResilientHttpClient;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * 共享汇率服务（amz-common 单一真相源）。
 * <p>
 * 合并原分散在三处的汇率实现：
 * <ul>
 *   <li>finance 模块 {@code CurrencyConverter}（exchangerate.host 实时）</li>
 *   <li>product 模块 {@code ExchangeRateService}（open.er-api.com 实时）</li>
 *   <li>multiplatform 模块 {@code PlatformCurrencyConverter}（原静态 yml，已废弃）</li>
 * </ul>
 * 统一为：启动从 yml 读取兜底汇率（{@code amz.exchange-rates}），之后定时（每小时）从
 * exchangerate.host（欧洲央行数据源，免费免 key）拉取最新汇率刷新内存缓存；
 * 拉取失败降级沿用上次缓存（首次失败沿用 yml 兜底），不阻断业务。
 * <p>
 * 汇率语义：1 单位原币 = rate CNY。
 * <p>
 * 仅 servlet 应用激活（网关为 WebFlux，无需汇率换算，自动跳过）。
 */
@Slf4j
@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class GlobalExchangeRateService {

    /** exchangerate.host 免费 API（base=USD），无需 key */
    @Value("${amz.exchange-rates-api-url:https://api.exchangerate.host/latest?base=USD}")
    private String exchangeRateApi;

    /**
     * 未知币种处理策略：
     * <ul>
     *   <li>false（默认）：按 1:1 兜底折算并 warn（兼容存量调用）；</li>
     *   <li>true：抛出 IllegalArgumentException 拒绝核算——财务严格模式，
     *       防止拼错币种把 JPY 当 CNY 记账。</li>
     * </ul>
     */
    @Value("${amz.exchange.strict-unknown:false}")
    private boolean strictUnknownCurrency;

    /** 汇率刷新连续失败计数（达到阈值升级为 error 日志）。 */
    private volatile int consecutiveRefreshFailures = 0;

    /** 兜底汇率（yml 配置），首次拉取失败时使用 */
    private final Map<String, BigDecimal> fallbackRates;

    /** 当前生效汇率缓存（volatile 引用 swap 保证可见性与原子性） */
    private volatile Map<String, BigDecimal> exchangeRates;

    /**
     * 统一出站 HTTP 通道（超时 + 重试 + 熔断 + 指标）。
     * 单元测试可传 null 并 override {@link #fetchRates()} 以避免真实网络调用。
     */
    private final ResilientHttpClient http;

    public GlobalExchangeRateService(
            @Value("#{${amz.exchange-rates:{}}}") Map<String, BigDecimal> rates,
            ResilientHttpClient http) {
        this.fallbackRates = rates != null ? new HashMap<>(rates) : new HashMap<>();
        this.exchangeRates = new HashMap<>(this.fallbackRates);
        this.http = http;
        log.info("共享汇率服务初始化：兜底币种 {}", this.fallbackRates.keySet());
    }

    /**
     * 启动即尝试拉取一次，保证进程起来后尽快具备实时汇率（不依赖 @EnableScheduling 是否开启）。
     * 失败则沿用 yml 兜底，不影响启动。
     */
    @PostConstruct
    public void init() {
        try {
            refreshRates();
        } catch (Exception e) {
            log.warn("启动汇率初始化失败，沿用 yml 兜底汇率：{}", e.getMessage());
        }
    }

    /**
     * 将原币金额转换为 CNY 本位币。
     *
     * @param originalAmount 原币金额
     * @param currency       原币币种
     * @return CNY 金额（2 位小数）
     */
    public BigDecimal toCny(BigDecimal originalAmount, String currency) {
        if (originalAmount == null || currency == null) {
            return BigDecimal.ZERO;
        }
        if ("CNY".equalsIgnoreCase(currency)) {
            return originalAmount.setScale(2, RoundingMode.HALF_UP);
        }
        BigDecimal rate = exchangeRates.get(currency.toUpperCase());
        if (rate == null) {
            if (strictUnknownCurrency) {
                throw new IllegalArgumentException(
                        "币种 " + currency + " 无汇率配置（amz.exchange.strict-unknown=true 拒绝 1:1 兜底），"
                                + "请补充 amz.exchange-rates 配置");
            }
            log.warn("币种 {} 无汇率配置，按 1:1 兜底折算，金额可能失真，请补充 amz.exchange-rates 配置", currency);
            return originalAmount.setScale(2, RoundingMode.HALF_UP);
        }
        return originalAmount.multiply(rate).setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * 获取某币种当前汇率（1 原币 = X CNY）。未知币种返回 1。
     */
    public BigDecimal getRate(String currency) {
        if (currency == null || "CNY".equalsIgnoreCase(currency)) {
            return BigDecimal.ONE;
        }
        return exchangeRates.getOrDefault(currency.toUpperCase(), BigDecimal.ONE);
    }

    /**
     * 计算 from→to 的交叉汇率（经 CNY 中转）。任一缺失返回 1（与旧 product.ExchangeRateService 行为一致）。
     */
    public BigDecimal getRate(String fromCurrency, String toCurrency) {
        if (fromCurrency == null || toCurrency == null) {
            return BigDecimal.ONE;
        }
        if (fromCurrency.equalsIgnoreCase(toCurrency)) {
            return BigDecimal.ONE;
        }
        BigDecimal fromCny = getRate(fromCurrency);
        BigDecimal toCny = getRate(toCurrency);
        if (toCny == null || toCny.signum() == 0) {
            return BigDecimal.ONE;
        }
        return fromCny.divide(toCny, 6, RoundingMode.HALF_UP);
    }

    /**
     * 每小时从 exchangerate.host 拉取最新汇率刷新缓存。
     * API 返回 base=USD 的交叉汇率，换算为「1 原币 = X CNY」：rate(currency→CNY) = rates["CNY"] / rates[currency]。
     * 拉取失败降级：保留现有缓存不变，不抛异常、不阻断业务。
     */
    @Scheduled(fixedRate = 60 * 60 * 1000L)
    public void refreshRates() {
        try {
            Map<String, Object> resp = fetchRates();
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
            refreshed.put("USD", cnyPerUsd.setScale(6, RoundingMode.HALF_UP));
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
            consecutiveRefreshFailures = 0;
            log.info("汇率缓存刷新成功：共 {} 个币种（来源 exchangerate.host）", refreshed.size());
        } catch (Exception e) {
            int failures = ++consecutiveRefreshFailures;
            // 连续失败 ≥3 次升级为 error：此时生产已长期运行在静态兜底汇率上，
            // 财务换算偏差需要被告警系统捕获，而非淹没在 warn 里
            if (failures >= 3) {
                log.error("汇率拉取连续失败 {} 次，当前运行在 yml 静态兜底汇率上，财务核算可能失真！原因: {}",
                        failures, e.getMessage());
            } else {
                log.warn("exchangerate.host 汇率拉取失败（连续第 {} 次），保留现有汇率缓存：{}",
                        failures, e.getMessage());
            }
        }
    }

    /**
     * 调用 exchangerate.host 拉取 base=USD 的汇率响应。
     * 抽成独立方法便于单元测试通过子类 override 注入 fake 响应，避免真实网络调用。
     *
     * @return API 响应（含 rates 字段），失败抛异常由调用方降级
     */
    protected Map<String, Object> fetchRates() {
        if (http == null) {
            throw new IllegalStateException("ResilientHttpClient 未注入，无法拉取汇率"
                    + "（测试场景请 override fetchRates）");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> resp = http.getForObject("exchange-rate", exchangeRateApi, Map.class);
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
