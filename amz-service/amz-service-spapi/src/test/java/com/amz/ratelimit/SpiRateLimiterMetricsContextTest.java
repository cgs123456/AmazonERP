package com.amz.ratelimit;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.autoconfigure.metrics.MetricsAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.metrics.export.prometheus.PrometheusMetricsExportAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("限流观测指标：Spring Boot 自动绑定 MeterBinder 并可由 Prometheus 抓取")
class SpiRateLimiterMetricsContextTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    MetricsAutoConfiguration.class,
                    PrometheusMetricsExportAutoConfiguration.class))
            .withBean(SpiRateLimiter.class)
            .withPropertyValues("management.prometheus.metrics.export.enabled=true");

    @Test
    @DisplayName("SpiRateLimiter 作为 MeterBinder Bean 自动绑定，scrape 含 spapi_ratelimit_limit")
    void meterBinderIsAutoBoundAndScraped() {
        contextRunner.run(context -> {
            assertNull(context.getStartupFailure(),
                    () -> "最小指标上下文启动失败：" + context.getStartupFailure());

            PrometheusMeterRegistry registry = context.getBean(PrometheusMeterRegistry.class);
            SpiRateLimiter limiter = context.getBean(SpiRateLimiter.class);
            limiter.updateLimit(1001L, "reports.createReport", "0.005");

            Gauge observed = registry.get("spapi.ratelimit.limit")
                    .tags("shopId", "1001", "operation", "reports.createReport",
                            "variant", "", "kind", "observed")
                    .gauge();
            assertNotNull(observed, "Spring Boot 必须自动调用 SpiRateLimiter.bindTo(MeterRegistry)");
            assertTrue(registry.scrape().contains("spapi_ratelimit_limit"),
                    "Prometheus 文本出口必须包含限流指标名");
        });
    }
}