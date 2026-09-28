package com.amz.config;

import com.amz.connector.HttpTransport;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * SP-API 出站传输装配：把 JDK {@link HttpClient} 适配为 {@link HttpTransport} Bean。
 * <p>
 * 连接超时 10s（与改造前各出站类字段初始化时的口径一致）；请求级超时由
 * {@code SpApiRequestFactory} 注入（SP-API 30s / 预签名下载 60s）。
 * <p>
 * 用<b>方法引用</b>而非 lambda：{@link HttpTransport#send} 是泛型方法，
 * lambda 无法实现（JDK 17 实测），方法引用可以。
 */
@Configuration
public class HttpClientConfig {

    @Bean
    public HttpTransport spApiHttpTransport() {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        return httpClient::send;
    }
}
