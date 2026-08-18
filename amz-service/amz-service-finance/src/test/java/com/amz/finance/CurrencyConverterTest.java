package com.amz.finance;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 多币种核算器测试。
 * <p>
 * 本类已重构为 {@link GlobalExchangeRateService}（amz-common 单一汇率真相源）的门面，
 * 因此测试职责收敛为「验证委托正确性」：入参原样透传、返回值原样回传、无额外加工。
 * <p>
 * 原先覆盖的汇率换算数学、定时刷新、响应异常降级等用例，已随实现一并迁移至
 * {@code amz-common} 的 {@code GlobalExchangeRateServiceTest}，避免重复维护两份等价断言。
 */
@ExtendWith(MockitoExtension.class)
class CurrencyConverterTest {

    @Mock
    private GlobalExchangeRateService delegate;

    @InjectMocks
    private CurrencyConverter converter;

    @Test
    @DisplayName("convertToCny：原样委托共享汇率服务，不做二次加工")
    void convertToCnyDelegates() {
        BigDecimal amount = new BigDecimal("100");
        when(delegate.toCny(amount, "USD")).thenReturn(new BigDecimal("725.00"));

        assertEquals(new BigDecimal("725.00"), converter.convertToCny(amount, "USD"));
        verify(delegate).toCny(amount, "USD");
    }

    @Test
    @DisplayName("convertToCny：null 入参同样透传，由共享服务统一做空值保护")
    void convertToCnyNullDelegates() {
        when(delegate.toCny(any(), eq("USD"))).thenReturn(BigDecimal.ZERO);

        assertEquals(BigDecimal.ZERO, converter.convertToCny(null, "USD"));
        verify(delegate).toCny(null, "USD");
    }

    @Test
    @DisplayName("getRate：原样委托共享汇率服务")
    void getRateDelegates() {
        when(delegate.getRate("USD")).thenReturn(new BigDecimal("7.25"));

        assertEquals(new BigDecimal("7.25"), converter.getRate("USD"));
        verify(delegate).getRate("USD");
    }
}
