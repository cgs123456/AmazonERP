package com.amz.client.impl;

import com.amz.client.KeepaClient;
import com.amz.exception.ConnectorException;
import com.google.gson.Gson;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Keepa 真实客户端。
 * <p>
 * 未配置 key 时 {@link #isAvailable()} 为 false，后台调度可整轮跳过；一旦手动调用
 * 或调度越过可用性检查，则必须抛出 {@link ConnectorException}，不再以 null 静默降级。
 * 非 2xx、网络异常和响应体为空同样视为调用失败，避免把“没调用/调用失败”伪装成“暂无数据”。
 */
@Slf4j
@Component
@Profile("!mock")
public class KeepaRealClient implements KeepaClient {

    private static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final String DEFAULT_API = "https://api.keepa.com/product";

    /**
     * HTTP 客户端，<b>惰性创建</b>。
     * <p>
     * <b>本字段原先的「惰性」是假的</b>（2026-10-19 修）：上一版把
     * {@code HttpClient.newBuilder()} 挪进了 {@link #defaultHttpClient()} 私有方法并写了
     * 「延迟创建」的注释，但<b>默认无参构造器直接调用它</b>。本类是
     * {@code @Component @Profile("!mock")}，生产档必然实例化 → 依旧在构造期打开
     * selector/loopback，任何 JVM 建不出网络栈的环境都会让 product 服务起不来。
     * <b>形状像惰性、行为不是惰性</b>，比直接写在字段上更危险，因为它看起来已经修过了。
     * <p>
     * 现在生产构造器传 null，表示「首次真正请求时再建」（见 {@link #httpClient()}）。
     * 注入 HttpClient 的包可见构造器行为不变：拿到什么用什么，<b>不经过访问器</b>，
     * 否则测试注入的 mock 会被真实网络栈悄悄替换。
     */
    private volatile HttpClient httpClient;

    private final String apiBaseUrl;
    private final Gson gson = new Gson();

    @Value("${keepa.api-key:}")
    private String apiKey;

    /** 生产档构造器：不在构造期创建 HttpClient（理由见字段注释）。 */
    public KeepaRealClient() {
        this(null, DEFAULT_API);
    }

    /**
     * 首次真正发请求时才创建客户端（双重检查，线程安全）。
     */
    private HttpClient httpClient() {
        HttpClient client = httpClient;
        if (client == null) {
            synchronized (this) {
                client = httpClient;
                if (client == null) {
                    client = HttpClient.newBuilder()
                            .connectTimeout(DEFAULT_CONNECT_TIMEOUT)
                            .build();
                    httpClient = client;
                }
            }
        }
        return client;
    }

    KeepaRealClient(HttpClient httpClient, String apiBaseUrl) {
        this.httpClient = httpClient;
        this.apiBaseUrl = apiBaseUrl;
    }

    @Override
    public boolean isAvailable() {
        return StringUtils.hasText(apiKey);
    }

    @Override
    public String getPriceHistory(String asin, int domain) {
        return callKeepa(asin, domain, "price");
    }

    @Override
    public String getRankHistory(String asin, int domain) {
        return callKeepa(asin, domain, "rank");
    }

    @Override
    public String getCompetitorAnalysis(String asin, int domain) {
        return callKeepa(asin, domain, "competitor");
    }

    private String callKeepa(String asin, int domain, String type) {
        if (!isAvailable()) {
            throw ConnectorException.notConfigured(
                    "Keepa", "keepa.api-key 未配置，禁止静默跳过或返回模拟数据");
        }
        if (!StringUtils.hasText(asin)) {
            throw new IllegalArgumentException("ASIN 不能为空");
        }
        if (domain <= 0) {
            throw new IllegalArgumentException("Keepa domain 必须为正整数: " + domain);
        }

        String url = String.format("%s?key=%s&domain=%d&asin=%s&stats=90",
                apiBaseUrl,
                URLEncoder.encode(apiKey, StandardCharsets.UTF_8),
                domain,
                URLEncoder.encode(asin.trim(), StandardCharsets.UTF_8));
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build();

        try {
            HttpResponse<String> response = httpClient().send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw ConnectorException.callFailed("Keepa",
                        "Keepa API 返回 HTTP " + response.statusCode() + "，type=" + type);
            }
            if (!StringUtils.hasText(response.body())) {
                throw ConnectorException.callFailed("Keepa",
                        "Keepa API 返回空响应体，type=" + type);
            }
            gson.fromJson(response.body(), Object.class);
            return response.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw ConnectorException.callFailed("Keepa",
                    "Keepa API 调用被中断，type=" + type, e);
        } catch (ConnectorException e) {
            log.error("Keepa API 调用失败：asin={} domain={} type={} reason={}",
                    asin, domain, type, e.getMessage());
            throw e;
        } catch (Exception e) {
            throw ConnectorException.callFailed("Keepa",
                    "Keepa API 调用失败，type=" + type + "：" + e.getMessage(), e);
        }
    }
}
