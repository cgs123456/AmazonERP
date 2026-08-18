package com.amz.http;

import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

/**
 * 弹性 HTTP 客户端装配。
 * <p>
 * 仅在 servlet 应用生效：网关为 WebFlux 且不发起业务出站 HTTP（由 Spring Cloud Gateway 自身转发），
 * 无需该组件，避免向网关注入无用 Bean。
 * <p>
 * Bean 命名 {@code resilientRestTemplate} 而非默认名，避免与各业务模块已有的
 * {@code RestTemplate} Bean（如带 LoadBalanced 注解的）冲突。
 */
@Configuration
@EnableConfigurationProperties(ResilientHttpProperties.class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class ResilientHttpConfig {

    private static final Logger log = LoggerFactory.getLogger(ResilientHttpConfig.class);

    /**
     * 带超时的 RestTemplate。
     * <p>
     * 使用 {@link SimpleClientHttpRequestFactory}（JDK HttpURLConnection）而非 Apache HttpClient：
     * 无需额外依赖，且本场景为低频外部 API 调用，连接池收益有限。
     * 若后续出现高并发出站需求，可在此替换为连接池化的工厂而不影响调用方。
     */
    @Bean(name = "resilientRestTemplate")
    @ConditionalOnMissingBean(name = "resilientRestTemplate")
    public RestTemplate resilientRestTemplate(ResilientHttpProperties props) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(props.getConnectTimeoutMs());
        factory.setReadTimeout(props.getReadTimeoutMs());
        log.info("弹性 HTTP 客户端初始化：connectTimeout={}ms readTimeout={}ms maxAttempts={} "
                        + "熔断阈值={}次/静默{}ms",
                props.getConnectTimeoutMs(), props.getReadTimeoutMs(), props.getMaxAttempts(),
                props.getCircuit().getFailureThreshold(), props.getCircuit().getOpenDurationMs());
        return new RestTemplate(factory);
    }

    /**
     * 统一出站 HTTP 通道。{@link MeterRegistry} 通过 {@link ObjectProvider} 软依赖注入，
     * 未引入 Micrometer 时退化为无指标模式，不影响可用性。
     */
    @Bean
    @ConditionalOnMissingBean
    public ResilientHttpClient resilientHttpClient(
            @Qualifier("resilientRestTemplate") RestTemplate resilientRestTemplate,
            ResilientHttpProperties props,
            ObjectProvider<MeterRegistry> meterRegistryProvider) {
        return new ResilientHttpClient(resilientRestTemplate, props,
                meterRegistryProvider.getIfAvailable());
    }
}
