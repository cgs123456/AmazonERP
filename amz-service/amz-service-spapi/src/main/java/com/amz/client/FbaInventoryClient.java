package com.amz.client;

import com.amz.auth.LwaTokenManager;
import com.amz.credential.ShopCredential;
import com.amz.credential.ShopCredentialStore;
import com.amz.connector.HttpTransport;
import com.amz.connector.MarketplaceRegistry;
import com.amz.connector.SpApiEndpointResolver;
import com.amz.connector.SpApiRequestFactory;
import com.amz.ratelimit.SpiRateLimiter;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import com.alibaba.csp.sentinel.annotation.SentinelResource;
import com.alibaba.csp.sentinel.slots.block.BlockException;

import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Amazon SP-API FBA Inventory v1 客户端。
 * <p>
 * 调用 /fba/inventory/v1/summaries 接口拉取 FBA 库存汇总。
 * 实现与 {@link OrdersClient} 一致的风格：LWA Token + 统一请求构造（SpApiRequestFactory）
 * + 429 重试 + NextToken 分页。
 * 在此基础上额外实现滑动窗口限流：30 秒内最多 25 次请求，超过则阻塞等待。
 */
@Component
public class FbaInventoryClient {

    private static final Logger log = LoggerFactory.getLogger(FbaInventoryClient.class);

    private static final String INVENTORY_PATH = "/fba/inventory/v1/summaries";

    /**
     * 官方 operationId（限流维度；数值见 contracts/fbaInventory.json 的 Usage Plan 表：
     * 2 req/s、burst 2）。旧实现用粗粒度 {@code "fba-inventory"} 命中兜底 1 req/s、burst 30，
     * 速率越权约 2 倍、突发越权约 15 倍。
     */
    private static final String OP_GET_INVENTORY_SUMMARIES = "fbaInventory.getInventorySummaries";

    /**
     * 429 限流重试次数上限。
     */
    private static final int MAX_RETRIES = 3;

    /**
     * 区域与官方端点统一由 {@link MarketplaceRegistry} 解析（P0-36 单一事实源）：
     * 未登记的 marketplaceId 或 region 直接抛 {@link com.amz.connector.UnknownMarketplaceException}，
     * 不再静默回落默认区域。
     */
    // ==================== 限流（统一走 SpiRateLimiter） ====================
    // 旧实现为本类私有 synchronized 滑动窗口：持锁 Thread.sleep 会阻塞所有店铺线程，
    // 且窗口按客户端实例而非 (shopId, endpoint) 维度统计。现与 OrdersClient 对齐，
    // 统一委托 SpiRateLimiter（按 shopId:endpoint 隔离 + x-amzn-RateLimit-Limit 动态收紧）。

    private final HttpTransport httpTransport;
    private final LwaTokenManager lwaTokenManager;
    private final ShopCredentialStore shopCredentialStore;
    private final SpApiRequestFactory requestFactory;

    /**
     * 统一滑动窗口限流器（fba-inventory 默认 25 req/30s，按店铺维度隔离）。
     */
    private final SpiRateLimiter spiRateLimiter;

    /**
     * Micrometer 指标注册表（软依赖，未配置时退化为无指标）。
     */
    private final ObjectProvider<MeterRegistry> meterRegistryProvider;

    public FbaInventoryClient(HttpTransport httpTransport,
                              LwaTokenManager lwaTokenManager,
                              ShopCredentialStore shopCredentialStore,
                              SpiRateLimiter spiRateLimiter,
                              SpApiRequestFactory requestFactory,
                              ObjectProvider<MeterRegistry> meterRegistryProvider) {
        this.httpTransport = httpTransport;
        this.lwaTokenManager = lwaTokenManager;
        this.shopCredentialStore = shopCredentialStore;
        this.spiRateLimiter = spiRateLimiter;
        this.requestFactory = requestFactory;
        this.meterRegistryProvider = meterRegistryProvider;
    }

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

        String region = MarketplaceRegistry.resolveRegion(marketplaceId);
        // P0-51：端点解析统一走 requestFactory（官方主机或非生产覆盖，与出站主机校验同源）
        SpApiEndpointResolver.Endpoint ep = requestFactory.resolveEndpoint(region);
        String endpoint = ep.baseUrl();
        String host = ep.host();
        // 分组码 NA/EU/FE 不是 AWS region：SigV4 作用域必须用真实 region（P0-48）
        String awsRegion = MarketplaceRegistry.resolveAwsRegion(region);

        String accessToken = lwaTokenManager.getToken(credential);

        List<JsonObject> allItems = new ArrayList<>();
        String nextToken = null;
        int pageCount = 0;

        do {
            // 发请求前按 (shopId, operationId) 维度令牌桶限流（与 OrdersClient 一致）
            spiRateLimiter.acquire(shopId, OP_GET_INVENTORY_SUMMARIES);

            TreeMap<String, String> params = new TreeMap<>();
            params.put("details", "true");
            params.put("granularityType", "Marketplace");
            params.put("granularityId", marketplaceId);
            params.put("marketplaceIds", marketplaceId);
            if (nextToken != null) {
                params.put("nextToken", nextToken);
            }
            String queryString = buildCanonicalQueryString(params);

            HttpRequest request = requestFactory.spApi(
                    "GET", endpoint, host, awsRegion, INVENTORY_PATH, queryString, null,
                    accessToken, credential.getAccessKey(), credential.getSecretKey());

            HttpResponse<String> response = sendWithRetry(request, shopId, OP_GET_INVENTORY_SUMMARIES);
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
     * 并读取 {@code x-amzn-RateLimit-Limit} 头收紧**该店铺该 operation** 的速率
     * （与 OrdersClient 对齐：观测值回升时自动恢复官方默认上限）。
     *
     * @param request     已构建好的 HTTP 请求
     * @param shopId      店铺 ID（限流按店铺隔离）
     * @param operationId 官方 operationId（{@code fbaInventory.getInventorySummaries}）
     */
    private HttpResponse<String> sendWithRetry(HttpRequest request, Long shopId, String operationId) {
        HttpResponse<String> response = null;
        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            try {
                response = httpTransport.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
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
                recordThrottle(operationId);
                // 读取 x-amzn-RateLimit-Limit（req/s），收紧本店铺该 operation 的速率
                String rateLimitHeader = response.headers()
                        .firstValue("x-amzn-RateLimit-Limit").orElse(null);
                if (rateLimitHeader != null && !rateLimitHeader.isBlank()) {
                    spiRateLimiter.updateLimit(shopId, operationId, rateLimitHeader);
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
     * @param operationId 官方 operationId（{@code fbaInventory.getInventorySummaries}）；
     *                    指标标签名仍为 {@code endpoint}，值升级为 operationId
     */
    private void recordThrottle(String operationId) {
        if (meterRegistryProvider == null) {
            return;
        }
        MeterRegistry registry = meterRegistryProvider.getIfAvailable();
        if (registry == null) {
            return;
        }
        try {
            registry.counter("spapi.throttle.count", "endpoint", operationId).increment();
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
}
