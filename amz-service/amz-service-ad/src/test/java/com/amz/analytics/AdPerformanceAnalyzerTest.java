package com.amz.analytics;

import com.amz.model.AdReport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNull;

class AdPerformanceAnalyzerTest {

    private final AdPerformanceAnalyzer analyzer = new AdPerformanceAnalyzer();

    @Test
    @DisplayName("销售额为正但成本缺失时不得抛异常，ACoS/ROAS 保持为空")
    void missingCostDoesNotBreakAcosAndRoas() {
        AdReport report = new AdReport();
        report.setSales(new BigDecimal("100.00"));
        report.setCost(null);

        assertDoesNotThrow(() -> analyzer.fillDerivedMetrics(report));
        assertNull(report.getAcos());
        assertNull(report.getRoas());
    }

    @Test
    @DisplayName("销售额为正但成本为零时不得除零，ACoS/ROAS 保持为空")
    void zeroCostDoesNotDivideByZero() {
        AdReport report = new AdReport();
        report.setSales(new BigDecimal("100.00"));
        report.setCost(BigDecimal.ZERO);

        assertDoesNotThrow(() -> analyzer.fillDerivedMetrics(report));
        assertNull(report.getAcos());
        assertNull(report.getRoas());
    }

    @Test
    @DisplayName("点击为正但成本缺失时 CPC 保持为空")
    void missingCostDoesNotBreakCpc() {
        AdReport report = new AdReport();
        report.setClicks(10L);
        report.setCost(null);

        assertDoesNotThrow(() -> analyzer.fillDerivedMetrics(report));
        assertNull(report.getCpc());
    }
}