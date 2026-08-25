package com.amz.client;

import com.amz.auth.AwsSigV4Signer;
import com.amz.auth.LwaTokenManager;
import com.amz.credential.ShopCredential;
import com.amz.credential.ShopCredentialStore;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import com.alibaba.csp.sentinel.annotation.SentinelResource;
import com.alibaba.csp.sentinel.slots.block.BlockException;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Amazon SP-API FBA Inventory v1 客户端。
 * <p>
 * 调用 /fba/inventory/v1/summaries 接口拉取 FBA 库存汇总。
 * 实现与 {@link OrdersClient} 一致的风格：LWA Token + Sig V4 签名 + 429 重试 + NextToken 分页。
 * 在此基础上额外实现滑动窗口限流：30 秒内最多 25 次请求，超过则阻塞等待。
 */
@Component
public class FbaInventoryClient {

    private static final Logger log = LoggerFactory.getLogger(FbaInventoryClient.class);

    private static final String INVENTORY_PATH = "/fba/inventory/v1/summaries";

    /** FBA Inventory 端点标识，用于限流指标维度。 */
    private static final String FBA_INVENTORY_ENDPOINT = "fba-inventory";

    /**
     * 429 限流重试次数上限。
     */
    private static final int MAX_RETRIES = 3;

    /**
     * SP-API 各区域端点（NA/EU/FE）。
     */
    private static final Map<String, String> SPAPI_ENDPOINTS = Map.of(
            "NA", "https://sellingpartnerapi-na.amazon.com",
            "EU", "https://sellingpartnerapi-eu.amazon.com",
            "FE", "https://sellingpartnerapi-fe.amazon.com"
    );

    /**
     * 常见 Marketplace ID 到区域（NA/EU/FE）的映射。
     */
    private static final Map<String, String> MARKETPLACE_REGION = Map.ofEntries(
            Map.entry("ATVPDKIKX0DER", "NA"),  // 美国
            Map.entry("A2EUQ1WTGCTBG2", "NA"),  // 加拿大
            Map.entry("A1AM78C64UM0Y8", "NA"),  // 墨西哥
            Map.entry("A1F83G8C2ARO7P", "EU"),  // 英国
            Map.entry("A13V1IB3VIYZZH", "EU"),  // 法国
            Map.entry("A1PA6795UKMFR9", "EU"),  // 德国
            Map.entry("A1RKKUPIHCS9HS", "EU"),  // 西班牙
            Map.entry("APJ6JRA9NG5V4", "EU"),   // 意大利
            Map.entry("A39IBJ37TRP1C6", "FE"),  // 澳大利亚
            Map.entry("A1VC38T7YXB528", "FE")   // 日本
    );

    // ==================== 限流（统一走 SpiRateLimiter） ====================
    // 旧实现为本类私有 synchronized 滑动窗口：持锁 Thread.sleep 会阻塞所有店铺线程，
    // 且窗口按客户端实例而非 (shopId, endpoint) 维度统计。现与 OrdersClient 对齐，
    // 统一委托 SpiRateLimiter（按 shopId:endpoint 隔离 + x-amzn-RateLimit-Limit 动态收紧）。

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    @Autowired
    private LwaTokenManager lwaTokenManager;

    @Autowired
    private AwsSigV4Signer awsSigV4Signer;

    @Autowired
    private ShopCredentialStore shopCredentialStore;

    /**
     * 统一滑动窗口限流器（fba-inventory 默认 25 req/30s，按店铺维度隔离）。
     */
    @Autowired
    private com.amz.ratelimit.SpiRateLimiter spiRateLimiter;

    /**
     * Micrometer 指标注册表（软依赖，未配置时退化为无指标）。
     */
    @Autowired(required = false)
    private ObjectProvider<MeterRegistry> meterRegistryProvider;

    /**
     * 拉取指定店铺在某 marketplace 下的全量 FBA 库存汇总。
     * 自动分页拉取所有 NextToken，details=true & granularityType=Marketplace。
     *
     * @param shopId        店铺 ID
     * @param marketplaceId Amazon Marketplace ID
     * @return 库存原始 JSON 列表（每条为 payload.inventorySummaries 数组中的一个对象）
     */
    @SentinelResource(value = "fetchAllInventory", fallback = "fetchAllInventoryFallback")
    public List<JsonObject> fetchAllInventory(Long shopId, String marketplaceId) {
        ShopCredential credential = shopCredentialStore.get(shopId);
        if (credential == null) {
            throw new IllegalArgumentException("No credential found for shopId=" + shopId);
        }

        String region = mapMarketplaceToRegion(marketplaceId);
        String endpoint = SPAPI_ENDPOINTS.get(region);
        if (endpoint == null) {
            throw new IllegalArgumentException("No SP-API endpoint for region=" + region);
        }

        String host = endpoint.replace("https://", "");
        String accessToken = lwaTokenManager.getToken(credential);

        List<JsonObject> allItems = new ArrayList<>();
        String nextToken = null;
        int pageCount = 0;

        do {
            // 发请求前按 (shopId, endpoint) 维度滑动窗口限流（与 OrdersClient 一致）
            spiRateLimiter.acquire(shopId, FBA_INVENTORY_ENDPOINT);

            TreeMap<String, String> params = new TreeMap<>();
            params.put("details", "true");
            params.put("granularityType", "Marketplace");
            params.put("granularityId", marketplaceId);
            params.put("marketplaceIds", marketplaceId);
            if (nextToken != null) {
                params.put("nextToken", nextToken);
            }
            String queryString = buildCanonicalQueryString(params);

            Map<String, String> signedHeaders = awsSigV4Signer.sign(
                    "GET", host, INVENTORY_PATH, queryString, "",
                    credential.getAccessKey(), credential.getSecretKey(), region);

            String url = endpoint + INVENTORY_PATH + "?" + queryString;
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(30))
                    .GET();
            signedHeaders.forEach(builder::header);
            // LWA access_token 通过 x-amz-access-token 透传，不参与 Sig V4 签名
            builder.header("x-amz-access-token", accessToken);

            HttpResponse<String> response = sendWithRetry(builder.build());
            if (response == null || response.statusCode() != 200) {
                int status = response == null ? -1 : response.statusCode();
                // 401/403：access_token 失效，主动驱逐 LWA 缓存（与 OrdersClient 对齐）
                if (status == 401 || status == 403) {
                    lwaTokenManager.invalidate(credential);
                    log.warn("fetchAllInventory got {} — LWA token cache invalidated shopId={}", status, shopId);
                }
                throw new RuntimeException("fetchAllInventory failed shopId=" + shopId
                        + " status=" + status
                        + " body=" + (response == null ? "" : response.body()));
            }

            JsonObject body = JsonParser.parseString(response.body()).getAsJsonObject();
            JsonObject payload = body.has("payload") && body.get("payload").isJsonObject()
                    ? body.getAsJsonObject("payload") : body;
            if (payload.has("inventorySummaries") && payload.get("inventorySummaries").isJsonArray()) {
                for (JsonElement e : payload.getAsJsonArray("inventorySummaries")) {
                    allItems.add(e.getAsJsonObject());
                }
            }
            nextToken = (payload.has("nextToken") && !payload.get("nextToken").isJsonNull())
                    ? payload.get("nextToken").getAsString() : null;
            pageCount++;
            log.debug("fetchAllInventory shopId={} page={} collected={} hasNext={}",
                    shopId, pageCount, allItems.size(), nextToken != null);
        } while (nextToken != null);

        log.info("fetchAllInventory done shopId={} pages={} total={}", shopId, pageCount, allItems.size());
        return allItems;
    }
    /**
     * fetchAllInventory 的 fallback 方法：原方法抛出异常或被熔断时返回空列表，避免调用方整体失败。
     */
    public List<JsonObject> fetchAllInventoryFallback(Long shopId, String marketplaceId,
                                                     Throwable e) {
        // 不返回空列表伪装成功：降级 / 异常时抛出异常，由上游（InventorySyncScheduler）记录 FAILED 并告警
        throw new RuntimeException("fetchAllInventory degraded (circuit-breaker/exception) shopId="
                + shopId + " marketplaceId=" + marketplaceId, e);
    }

    /**
     * 发送请求，遇到 429 限流时按指数退避重试，
     * 并读取 {@code x-amzn-RateLimit-Limit} 头动态收紧本地窗口（与 OrdersClient 对齐）。
     */
    private HttpResponse<String> sendWithRetry(HttpRequest request) {
        HttpResponse<String> response = null;
        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            try {
                response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("HTTP send interrupted attempt={}", attempt);
                return null;
            } catch (Exception e) {
                log.warn("HTTP send error attempt={} {}", attempt, e.getMessage());
                sleep((1L << attempt) * 1000L);
                continue;
            }
            if (response.statusCode() == 429) {
                recordThrottle(FBA_INVENTORY_ENDPOINT);
                // 读取 x-amzn-RateLimit-Limit（req/s），收紧本地窗口，降低后续被限流概率
                String rateLimitHeader = response.headers()
                        .firstValue("x-amzn-RateLimit-Limit").orElse(null);
                if (rateLimitHeader != null && !rateLimitHeader.isBlank()) {
                    spiRateLimiter.updateLimit(FBA_INVENTORY_ENDPOINT, rateLimitHeader);
                }
                long backoff = (1L << attempt) * 1000L;
                log.warn("Rate limited (429), retrying after {}ms attempt={}", backoff, attempt);
                sleep(backoff);
                continue;
            }
            return response;
        }
        return response;
    }

    /**
     * 记录 SP-API 429 限流触发次数到 Micrometer，供 Prometheus 抓取。
     * 软依赖：未引入指标注册表时不抛异常、不影响主链路。
     *
     * @param endpoint SP-API 资源标识（如 {@code "fba-inventory"}）
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
     * 由 TreeMap 保证参数按字典序排列，并做符合 AWS 规范的 URI 编码，
     * 该串同时用于签名与最终 URL。
     */
    private String buildCanonicalQueryString(TreeMap<String, String> params) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : params.entrySet()) {
            if (sb.length() > 0) {
                sb.append("&");
            }
            sb.append(encode(e.getKey())).append("=").append(encode(e.getValue()));
        }
        return sb.toString();
    }

    /**
     * AWS 规范的 URI 编码：空格用 %20，* 用 %2A，~ 保留不编码。
     */
    private String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8)
                .replace("+", "%20")
                .replace("*", "%2A")
                .replace("%7E", "~");
    }

    /**
     * 将 Marketplace ID 映射到 SP-API 区域（NA/EU/FE），未知时默认 NA。
     */
    public String mapMarketplaceToRegion(String marketplaceId) {
        return MARKETPLACE_REGION.getOrDefault(marketplaceId, "NA");
    }
}
