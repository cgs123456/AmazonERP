package com.amz.finance;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 共享汇率服务单元测试（纯 JUnit 5，不依赖 Spring 容器与网络）。
 * <p>
 * 通过子类 override {@link GlobalExchangeRateService#fetchRates()} 注入假响应，
 * 覆盖：多币种换算数学、大小写不敏感、空值保护、未知币种降级、交叉汇率、
 * 以及定时刷新的成功路径与四类降级路径（异常 / 缺 rates / 缺 CNY / 币种未覆盖）。
 * <p>
 * 本文件合并了原 finance 模块 {@code CurrencyConverterTest} 的全部边界用例
 * —— 三套重复汇率实现合并为单一真相源后，断言也随之收敛到此处。
 */
class GlobalExchangeRateServiceTest {

    /** 子类 override fetchRates 注入 fake 响应（Supplier 形式以支持模拟抛异常）。 */
    static class FakeService extends GlobalExchangeRateService {

        Supplier<Map<String, Object>> fake = () -> null;

        FakeService(Map<String, BigDecimal> fallback) {
            // http 传 null：fetchRates 已被 override，不会触达真实出站通道
            super(fallback, null);
        }

        @Override
        protected Map<String, Object> fetchRates() {
            return fake.get();
        }
    }

    private FakeService build(Map<String, BigDecimal> fallback) {
        return new FakeService(fallback);
    }

    private Map<String, BigDecimal> defaultRates() {
        Map<String, BigDecimal> r = new HashMap<>();
        r.put("USD", new BigDecimal("7.25"));
        r.put("EUR", new BigDecimal("7.85"));
        r.put("GBP", new BigDecimal("9.20"));
        r.put("JPY", new BigDecimal("0.046"));
        return r;
    }

    // ---------------------------------------------------------------- 换算数学

    @Test
    @DisplayName("toCny：USD 按汇率换算保留两位小数")
    void toCnyUsdToCny() {
        GlobalExchangeRateService s = build(defaultRates());
        assertEquals(new BigDecimal("725.00"), s.toCny(new BigDecimal("100"), "USD"));
    }

    @Test
    @DisplayName("toCny：EUR 多币种换算")
    void toCnyEurToCny() {
        GlobalExchangeRateService s = build(defaultRates());
        assertEquals(new BigDecimal("785.00"), s.toCny(new BigDecimal("100"), "EUR"));
    }

    @Test
    @DisplayName("toCny：JPY 小汇率换算不丢精度")
    void toCnyJpySmallRate() {
        GlobalExchangeRateService s = build(defaultRates());
        // 1000 * 0.046 = 46.00
        assertEquals(new BigDecimal("46.00"), s.toCny(new BigDecimal("1000"), "JPY"));
    }

    @Test
    @DisplayName("toCny：CNY 原币即本位币，仅规整为两位小数")
    void toCnyCny() {
        GlobalExchangeRateService s = build(defaultRates());
        assertEquals(new BigDecimal("123.46"), s.toCny(new BigDecimal("123.456"), "CNY"));
    }

    @Test
    @DisplayName("toCny：币种大小写不敏感，usd 等价 USD")
    void toCnyCaseInsensitive() {
        GlobalExchangeRateService s = build(defaultRates());
        assertEquals(new BigDecimal("725.00"), s.toCny(new BigDecimal("100"), "usd"));
    }

    @Test
    @DisplayName("toCny：未知币种按 1:1 兜底而非抛异常（避免阻断账务流程）")
    void toCnyUnknownFallback() {
        GlobalExchangeRateService s = build(defaultRates());
        assertEquals(new BigDecimal("100.00"), s.toCny(new BigDecimal("100"), "XYZ"));
    }

    @Test
    @DisplayName("toCny：金额或币种为 null 返回 0")
    void toCnyNullInputs() {
        GlobalExchangeRateService s = build(defaultRates());
        assertEquals(BigDecimal.ZERO, s.toCny(null, "USD"));
        assertEquals(BigDecimal.ZERO, s.toCny(new BigDecimal("100"), null));
    }

    // ---------------------------------------------------------------- getRate

    @Test
    @DisplayName("getRate：已知币种返回配置汇率")
    void getRateKnown() {
        assertEquals(new BigDecimal("7.25"), build(defaultRates()).getRate("USD"));
    }

    @Test
    @DisplayName("getRate：CNY 恒为 1")
    void getRateCny() {
        assertEquals(BigDecimal.ONE, build(defaultRates()).getRate("CNY"));
    }

    @Test
    @DisplayName("getRate：未知币种降级为 1")
    void getRateUnknownFallback() {
        assertEquals(BigDecimal.ONE, build(defaultRates()).getRate("XYZ"));
    }

    @Test
    @DisplayName("getRate(from,to)：经 CNY 中转计算交叉汇率")
    void getRateCross() {
        GlobalExchangeRateService s = build(defaultRates());
        // USD→EUR = 7.25 / 7.85
        BigDecimal expected = new BigDecimal("7.25").divide(new BigDecimal("7.85"), 6, RoundingMode.HALF_UP);
        assertEquals(0, expected.compareTo(s.getRate("USD", "EUR")));
        // 同币种恒为 1
        assertEquals(0, BigDecimal.ONE.compareTo(s.getRate("USD", "USD")));
    }

    // ---------------------------------------------------------------- 定时刷新

    @Test
    @DisplayName("refreshRates：从 fake API 刷新并影响后续换算（USD/EUR 交叉汇率）")
    void refreshRatesSuccess() {
        // exchangerate.host 语义：1 USD = rates[ccy]
        Map<String, Object> rates = new HashMap<>();
        rates.put("USD", 1);
        rates.put("CNY", 7.15);
        rates.put("EUR", 0.92);
        rates.put("GBP", 0.79);
        rates.put("JPY", 145);
        Map<String, Object> fake = new HashMap<>();
        fake.put("rates", rates);

        FakeService s = build(new HashMap<>());
        // 刷新前无 USD 汇率，按 1:1
        assertEquals(new BigDecimal("100.00"), s.toCny(new BigDecimal("100"), "USD"));

        s.fake = () -> fake;
        s.refreshRates();

        // 刷新后 USD = 7.15
        assertEquals(new BigDecimal("715.00"), s.toCny(new BigDecimal("100"), "USD"));
        // EUR：cnyPerEur = 7.15 / 0.92
        BigDecimal expectedEur = new BigDecimal("7.15").divide(new BigDecimal("0.92"), 6, RoundingMode.HALF_UP);
        assertEquals(expectedEur.multiply(new BigDecimal("100")).setScale(2, RoundingMode.HALF_UP),
                s.toCny(new BigDecimal("100"), "EUR"));
    }

    @Test
    @DisplayName("refreshRates 降级：拉取抛异常时不抛出且保留既有缓存")
    void refreshRatesExceptionKeepsCache() {
        FakeService s = build(defaultRates());
        s.fake = () -> {
            throw new RuntimeException("simulated network error");
        };
        assertDoesNotThrow(s::refreshRates);
        assertNotNull(s.getRate("USD"));
        // 兜底汇率仍生效
        assertEquals(new BigDecimal("725.00"), s.toCny(new BigDecimal("100"), "USD"));
    }

    @Test
    @DisplayName("refreshRates 降级：空响应保留既有缓存")
    void refreshRatesNullResponseKeepsCache() {
        FakeService s = build(defaultRates());
        s.fake = () -> null;
        assertDoesNotThrow(s::refreshRates);
        assertEquals(new BigDecimal("725.00"), s.toCny(new BigDecimal("100"), "USD"));
    }

    @Test
    @DisplayName("refreshRates 降级：响应缺 rates 字段保留既有缓存")
    void refreshRatesMalformedKeepsCache() {
        Map<String, Object> bad = new HashMap<>();
        bad.put("foo", "bar");

        FakeService s = build(defaultRates());
        s.fake = () -> bad;
        s.refreshRates();
        assertEquals(new BigDecimal("725.00"), s.toCny(new BigDecimal("100"), "USD"));
    }

    @Test
    @DisplayName("refreshRates 降级：响应缺 CNY 基准汇率保留既有缓存")
    void refreshRatesMissingCnyKeepsCache() {
        Map<String, Object> rates = new HashMap<>();
        rates.put("USD", 1);
        rates.put("EUR", 0.92);
        Map<String, Object> fake = new HashMap<>();
        fake.put("rates", rates);

        FakeService s = build(defaultRates());
        s.fake = () -> fake;
        s.refreshRates();
        assertEquals(new BigDecimal("725.00"), s.toCny(new BigDecimal("100"), "USD"));
    }

    @Test
    @DisplayName("refreshRates：API 未返回的旧币种保留旧值，避免偶发丢币种导致金额失真")
    void refreshRatesPreservesUncoveredCurrencies() {
        Map<String, Object> rates = new HashMap<>();
        rates.put("USD", 1);
        rates.put("CNY", 7.15);
        // GBP / JPY 未出现在响应中
        Map<String, Object> fake = new HashMap<>();
        fake.put("rates", rates);

        FakeService s = build(defaultRates());
        s.fake = () -> fake;
        s.refreshRates();

        assertEquals(new BigDecimal("715.00"), s.toCny(new BigDecimal("100"), "USD"));
        assertEquals(0, new BigDecimal("9.20").compareTo(s.getRate("GBP")),
                "GBP 应保留旧汇率 9.20，实际：" + s.getRate("GBP"));
    }
}
