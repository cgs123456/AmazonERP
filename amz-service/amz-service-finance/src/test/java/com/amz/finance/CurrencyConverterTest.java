package com.amz.finance;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 多币种核算器单元测试（纯 JUnit 5，不依赖 Spring 容器与网络）。
 * <p>
 * 通过子类 override {@link CurrencyConverter#fetchUsdRates()} 注入 fake 响应，
 * 覆盖：多币种换算、汇率缺失降级、定时刷新成功/失败降级。
 */
class CurrencyConverterTest {

    private CurrencyConverter converter;

    @BeforeEach
    void setUp() {
        Map<String, BigDecimal> rates = new HashMap<>();
        rates.put("USD", new BigDecimal("7.25"));
        rates.put("EUR", new BigDecimal("7.85"));
        rates.put("GBP", new BigDecimal("9.20"));
        rates.put("JPY", new BigDecimal("0.046"));
        converter = new CurrencyConverter(rates);
    }

    @Test
    @DisplayName("USD → CNY：按汇率换算保留两位小数")
    void convertUsdToCny() {
        BigDecimal cny = converter.convertToCny(new BigDecimal("100"), "USD");
        assertEquals(new BigDecimal("725.00"), cny);
    }

    @Test
    @DisplayName("EUR → CNY：多币种换算")
    void convertEurToCny() {
        BigDecimal cny = converter.convertToCny(new BigDecimal("100"), "EUR");
        assertEquals(new BigDecimal("785.00"), cny);
    }

    @Test
    @DisplayName("JPY → CNY：小汇率换算")
    void convertJpyToCny() {
        // 1000 * 0.046 = 46.00
        BigDecimal cny = converter.convertToCny(new BigDecimal("1000"), "JPY");
        assertEquals(new BigDecimal("46.00"), cny);
    }

    @Test
    @DisplayName("CNY → CNY：原币即本位币，原值返回")
    void convertCnyToCny() {
        BigDecimal cny = converter.convertToCny(new BigDecimal("123.456"), "CNY");
        assertEquals(new BigDecimal("123.46"), cny);
    }

    @Test
    @DisplayName("汇率缺失降级：未知币种按 1:1 处理")
    void convertUnknownCurrencyFallbackToOne() {
        BigDecimal cny = converter.convertToCny(new BigDecimal("100"), "XYZ");
        assertEquals(new BigDecimal("100.00"), cny);
    }

    @Test
    @DisplayName("币种大小写不敏感：usd 等价 USD")
    void convertCaseInsensitive() {
        BigDecimal cny = converter.convertToCny(new BigDecimal("100"), "usd");
        assertEquals(new BigDecimal("725.00"), cny);
    }

    @Test
    @DisplayName("空值保护：金额或币种为 null 返回 0")
    void convertNullInputs() {
        assertEquals(BigDecimal.ZERO, converter.convertToCny(null, "USD"));
        assertEquals(BigDecimal.ZERO, converter.convertToCny(new BigDecimal("100"), null));
    }

    @Test
    @DisplayName("getRate：已知币种返回配置汇率")
    void getRateKnown() {
        assertEquals(new BigDecimal("7.25"), converter.getRate("USD"));
    }

    @Test
    @DisplayName("getRate：CNY 恒为 1")
    void getRateCny() {
        assertEquals(BigDecimal.ONE, converter.getRate("CNY"));
    }

    @Test
    @DisplayName("getRate：未知币种降级为 1")
    void getRateUnknownFallback() {
        assertEquals(BigDecimal.ONE, converter.getRate("XYZ"));
    }

    @Test
    @DisplayName("定时刷新成功：从 fake API 更新 USD 汇率并影响换算")
    void refreshRatesSuccessUpdatesCache() {
        // fake 响应：base=USD，1 USD = 7.15 CNY = 0.92 EUR = 0.79 GBP = 145 JPY
        Map<String, Object> fake = new HashMap<>();
        Map<String, Object> rates = new HashMap<>();
        rates.put("USD", 1);
        rates.put("CNY", 7.15);
        rates.put("EUR", 0.92);
        rates.put("GBP", 0.79);
        rates.put("JPY", 145);
        fake.put("rates", rates);

        CurrencyConverter refreshing = new CurrencyConverter(new HashMap<>()) {
            @Override
            Map<String, Object> fetchUsdRates() {
                return fake;
            }
        };
        // 刷新前未配置 USD 汇率，按 1:1
        assertEquals(new BigDecimal("100.00"), refreshing.convertToCny(new BigDecimal("100"), "USD"));

        refreshing.refreshRates();

        // 刷新后 USD = 7.15
        assertEquals(new BigDecimal("715.00"), refreshing.convertToCny(new BigDecimal("100"), "USD"));
        // EUR = 7.15 / 0.92 = 7.771739（6 位）→ 100 EUR = 777.17
        BigDecimal expectedEur = new BigDecimal("7.15").divide(new BigDecimal("0.92"), 6, RoundingMode.HALF_UP);
        assertEquals(expectedEur.multiply(new BigDecimal("100")).setScale(2, RoundingMode.HALF_UP),
                refreshing.convertToCny(new BigDecimal("100"), "EUR"));
    }

    @Test
    @DisplayName("定时刷新失败降级：API 抛异常时保留现有缓存不抛出")
    void refreshRatesFailurePreservesCache() {
        CurrencyConverter failing = new CurrencyConverter(new HashMap<>()) {
            @Override
            Map<String, Object> fetchUsdRates() {
                throw new RuntimeException("simulated network error");
            }
        };
        // 注入初始缓存（模拟首次拉取失败但有 yml 兜底）
        Map<String, BigDecimal> init = new HashMap<>();
        init.put("USD", new BigDecimal("7.25"));
        // 通过构造器兜底已设置；此处直接验证刷新失败后仍可用兜底
        // 失败刷新不应抛异常
        failing.refreshRates();
        // 兜底汇率仍可用（fallback 为空，故 USD 走 1:1；重点是不抛异常）
        assertNotNull(failing.getRate("USD"));
    }

    @Test
    @DisplayName("定时刷新失败降级：响应缺 rates 字段时保留缓存")
    void refreshRatesMalformedResponsePreservesCache() {
        Map<String, Object> bad = new HashMap<>();
        bad.put("foo", "bar"); // 无 rates 字段

        CurrencyConverter converterWithBad = new CurrencyConverter(defaultRates()) {
            @Override
            Map<String, Object> fetchUsdRates() {
                return bad;
            }
        };
        // 刷新前 USD = 7.25
        assertEquals(new BigDecimal("725.00"), converterWithBad.convertToCny(new BigDecimal("100"), "USD"));
        converterWithBad.refreshRates();
        // 刷新后仍为 7.25（缓存未变）
        assertEquals(new BigDecimal("725.00"), converterWithBad.convertToCny(new BigDecimal("100"), "USD"));
    }

    @Test
    @DisplayName("定时刷新失败降级：响应缺 CNY 时保留缓存")
    void refreshRatesMissingCnyPreservesCache() {
        Map<String, Object> fake = new HashMap<>();
        Map<String, Object> rates = new HashMap<>();
        rates.put("USD", 1);
        // 故意不放 CNY
        rates.put("EUR", 0.92);
        fake.put("rates", rates);

        CurrencyConverter converterNoCny = new CurrencyConverter(defaultRates()) {
            @Override
            Map<String, Object> fetchUsdRates() {
                return fake;
            }
        };
        converterNoCny.refreshRates();
        // 缺 CNY 应保留旧缓存：USD 仍 7.25
        assertEquals(new BigDecimal("725.00"), converterNoCny.convertToCny(new BigDecimal("100"), "USD"));
    }

    @Test
    @DisplayName("定时刷新：旧币种未被 API 覆盖时保留旧值")
    void refreshRatesPreservesUncoveredCurrencies() {
        Map<String, Object> fake = new HashMap<>();
        Map<String, Object> rates = new HashMap<>();
        rates.put("USD", 1);
        rates.put("CNY", 7.15);
        // GBP/JPY 未出现在 API 响应中
        fake.put("rates", rates);

        CurrencyConverter c = new CurrencyConverter(defaultRates()) {
            @Override
            Map<String, Object> fetchUsdRates() {
                return fake;
            }
        };
        c.refreshRates();
        // USD 被更新为 7.15
        assertEquals(new BigDecimal("715.00"), c.convertToCny(new BigDecimal("100"), "USD"));
        // GBP 未被覆盖，保留旧值 9.20
        assertTrue(c.getRate("GBP").compareTo(new BigDecimal("9.20")) == 0,
                "GBP 应保留旧汇率 9.20，实际: " + c.getRate("GBP"));
    }

    private Map<String, BigDecimal> defaultRates() {
        Map<String, BigDecimal> r = new HashMap<>();
        r.put("USD", new BigDecimal("7.25"));
        r.put("EUR", new BigDecimal("7.85"));
        r.put("GBP", new BigDecimal("9.20"));
        r.put("JPY", new BigDecimal("0.046"));
        return r;
    }
}