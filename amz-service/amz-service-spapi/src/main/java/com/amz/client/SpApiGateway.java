package com.amz.client;

import com.amz.auth.AwsSigV4Signer;
import com.amz.auth.LwaTokenManager;
import com.amz.credential.ShopCredential;
import com.amz.credential.ShopCredentialStore;
import com.amz.connector.MarketplaceRegistry;
import com.amz.ratelimit.SpiRateLimiter;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.TreeMap;

/**
 * SP-API 统一调用网关：签名 + LWA Token + 限流 + 429 退避重试 + 指标。
 * <p>
 * 与 {@link FeedsClient} / {@link OrdersClient} 内部实现保持同一套容错模式
 * （429 指数退避、RateLimit 头回填本地窗口、401/403 驱逐 LWA 缓存），
 * 差别仅在于抽成了共享组件 —— 供财务域新增的 Reports / Finances / Fees 客户端复用，
 * 避免三个客户端各自复制一份百行级的 HTTP 样板。
 * <p>
 * 既有客户端（Orders / Feeds / FbaInventory）不迁移到本网关，
 * 避免「顺手重构」引入无关回归。
 */
@Component
public class SpApiGateway {

    private static final Logger log = LoggerFactory.getLogger(SpApiGateway.class);

    private static final int MAX_RETRIES = 3;

    /**
     * 区域与官方端点统一由 {@link MarketplaceRegistry} 解析（P0-36 单一事实源）：
     * 未登记的 marketplaceId 或 region 直接抛 {@link com.amz.connector.UnknownMarketplaceException}，
     * 不再静默回落默认区域。
     */
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    @Autowired
    private LwaTokenManager lwaTokenManager;

    @Autowired
    private AwsSigV4Signer awsSigV4Signer;

    @Autowired
    private ShopCredentialStore shopCredentialStore;

    @Autowired
    private SpiRateLimiter spiRateLimiter;

    /** Micrometer 指标注册表（软依赖，未配置时退化为无指标）。 */
    @Autowired(required = false)
    private ObjectProvider<MeterRegistry> meterRegistryProvider;

    /**
     * 解析店铺凭证与 SP-API 端点/区域信息（P0-36 fail-closed）。
     * 优先用显式 marketplaceId 映射区域；缺失时回退到凭证登记的 marketplaceId，
     * 再回退到凭证区域；三者都缺失或非法时**抛异常**（与 FeedsClient 的回退链一致）。
     */
    public ResolvedShop resolveShop(Long shopId, String marketplaceId) {
        ShopCredential credential = shopCredentialStore.get(shopId);
        if (credential == null) {
            throw new IllegalArgumentException("No credential found for shopId=" + shopId);
        }
        // fail-closed（P0-36）：显式 marketplaceId → 凭证 marketplaceId → 凭证 region → 抛异常。
        // 旧实现在三者全空时静默回落 NA 端点，会把请求发到未经确认的区域。
        String region = MarketplaceRegistry.resolveRegion(
                marketplaceId, credential.getMarketplaceId(), credential.getRegion(), "shopId=" + shopId);
        String endpoint = MarketplaceRegistry.resolveEndpointForRegion(region);
        String host = MarketplaceRegistry.resolveHost(region);
        return new ResolvedShop(credential, region, endpoint, host);
    }

    /**
     * 发起一次 SP-API JSON 调用（带限流、429 退避重试与指标）。
     *
     * @param method      HTTP 方法（GET / POST）
     * @param shop        已解析的店铺信息（含端点与凭证）
     * @param endpointTag 限流端点标识（如 "reports"）
     * @param path        请求路径，如 /reports/2021-09-01/reports
     * @param query       规范查询串（参数名字典序、URI 编码、&amp; 拼接；无查询传 null）
     * @param body        JSON 请求体（GET 传 null）
     * @return 响应 JSON（HTTP 200 才返回，否则抛 RuntimeException）
     */
    public JsonObject callJson(String method, ResolvedShop shop, String endpointTag,
                               String path, String query, String body) {
        String accessToken = lwaTokenManager.getToken(shop.credential);
        spiRateLimiter.acquire(shop.credential.getShopId(), endpointTag);

        String canonicalQuery = query == null ? "" : query;
        String canonicalBody = body == null ? "" : body;
        Map<String, String> signedHeaders = awsSigV4Signer.sign(
                method, shop.host, path, canonicalQuery, canonicalBody,
                shop.credential.getAccessKey(), shop.credential.getSecretKey(), shop.region);

        String uri = shop.endpoint + path
                + (canonicalQuery.isEmpty() ? "" : "?" + canonicalQuery);
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(uri))
                .timeout(Duration.ofSeconds(30));
        if ("POST".equalsIgnoreCase(method)) {
            builder.header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(canonicalBody, StandardCharsets.UTF_8));
        } else {
            builder.GET();
        }
        signedHeaders.forEach(builder::header);
        builder.header("x-amz-access-token", accessToken);

        HttpResponse<String> response = sendWithRetry(builder.build(), endpointTag, shop);
        int status = response == null ? -1 : response.statusCode();
        if (status == 401 || status == 403) {
            lwaTokenManager.invalidate(shop.credential);
            log.warn("SP-API call got {} — LWA token cache invalidated shopId={} path={}",
                    status, shop.credential.getShopId(), path);
        }
        if (response == null || status != 200) {
            throw new RuntimeException("SP-API call failed method=" + method + " path=" + path
                    + " status=" + status
                    + " body=" + (response == null ? "" : response.body()));
        }
        return JsonParser.parseString(response.body()).getAsJsonObject();
    }

    /**
     * 下载任意 URL 的原始字节（用于 S3 预签名文档地址，无需再签名）。
     */
    public byte[] downloadBytes(String url) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(60))
                .GET()
                .build();
        try {
            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200) {
                throw new RuntimeException("download failed status=" + response.statusCode() + " url=" + url);
            }
            return response.body();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("download error: " + e.getMessage(), e);
        }
    }

    /**
     * 发送 HTTP 请求，遇到 429 限流时按指数退避重试
     * （模式与 FeedsClient.sendWithRetry 一致：RateLimit 头回填本地窗口）。
     */
    private HttpResponse<String> sendWithRetry(HttpRequest request, String endpointTag, ResolvedShop shop) {
        HttpResponse<String> response = null;
        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            try {
                response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("HTTP send interrupted attempt={}", attempt);
                return null;
            } catch (IOException e) {
                log.warn("HTTP send error attempt={} {}", attempt, e.getMessage());
                sleep((1L << attempt) * 1000L);
                continue;
            }
            if (response.statusCode() == 429) {
                recordThrottle(endpointTag);
                String rateLimitHeader = response.headers()
                        .firstValue("x-amzn-RateLimit-Limit").orElse(null);
                if (rateLimitHeader != null && !rateLimitHeader.isBlank()) {
                    spiRateLimiter.updateLimit(endpointTag, rateLimitHeader);
                }
                long backoff = (1L << attempt) * 1000L;
                log.warn("Rate limited (429), retrying after {}ms attempt={} endpoint={}",
                        backoff, attempt, endpointTag);
                sleep(backoff);
                continue;
            }
            return response;
        }
        return response;
    }

    /**
     * 记录 SP-API 429 限流触发次数到 Micrometer（软依赖，不影响主链路）。
     */
    private void recordThrottle(String endpoint) {
        if (meterRegistryProvider == null) {
            return;
        }
        MeterRegistry registry = meterRegistryProvider.getIfAvailable();
        if (registry == null) {
            return;
        }
        try {
            registry.counter("spapi.throttle.count", "endpoint", endpoint).increment();
        } catch (Exception e) {
            log.debug("SP-API throttle metric record failed: {}", e.getMessage());
        }
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 构造规范查询串：参数名按字典序排序 + URI 编码 + &amp; 拼接
     * （Sig V4 的 canonical query 要求，调用方无需自行排序）。
     */
    public static String canonicalQuery(Map<String, String> params) {
        if (params == null || params.isEmpty()) {
            return "";
        }
        TreeMap<String, String> sorted = new TreeMap<>(params);
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : sorted.entrySet()) {
            if (sb.length() > 0) {
                sb.append('&');
            }
            sb.append(urlEncode(e.getKey())).append('=').append(urlEncode(e.getValue()));
        }
        return sb.toString();
    }

    private static String urlEncode(String s) {
        return java.net.URLEncoder.encode(s, StandardCharsets.UTF_8)
                .replace("+", "%20").replace("*", "%2A").replace("%7E", "~");
    }

    /**
     * 店铺解析结果：凭证 + 区域 + 端点 + 主机名。
     */
    public static final class ResolvedShop {
        public final ShopCredential credential;
        public final String region;
        public final String endpoint;
        public final String host;

        public ResolvedShop(ShopCredential credential, String region, String endpoint, String host) {
            this.credential = credential;
            this.region = region;
            this.endpoint = endpoint;
            this.host = host;
        }
    }
}
