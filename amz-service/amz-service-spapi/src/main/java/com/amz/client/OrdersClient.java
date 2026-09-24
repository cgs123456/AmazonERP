package com.amz.client;

import com.amz.auth.LwaTokenManager;
import com.amz.credential.ShopCredential;
import com.amz.credential.ShopCredentialStore;
import com.amz.connector.HttpTransport;
import com.amz.connector.MarketplaceRegistry;
import com.amz.connector.SpApiRequestFactory;
import com.amz.ratelimit.SpiRateLimiter;
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
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Amazon SP-API Orders v0 客户端。
 * <p>
 * 使用 {@link com.amz.connector.HttpTransport} 发送、Gson 解析响应；
 * 出站请求统一由 {@link com.amz.connector.SpApiRequestFactory} 构造
 * （必填 user-agent / 条件 Sig V4 签名 / LWA token）。
 * 内置 429 限流重试与 NextToken 分页拉取。
 */
@Component
public class OrdersClient {

    private static final Logger log = LoggerFactory.getLogger(OrdersClient.class);

    /**
     * ISO-8601 瞬时时间格式化器，用于 CreatedAfter 等参数。
     */
    private static final DateTimeFormatter ISO_INSTANT = DateTimeFormatter.ISO_INSTANT;

    private static final String ORDERS_PATH = "/orders/v0/orders";

    /**
     * 429 限流重试次数上限。
     */
    private static final int MAX_RETRIES = 3;

    /**
     * 区域与官方端点统一由 {@link MarketplaceRegistry} 解析（P0-36 单一事实源）：
     * 未登记的 marketplaceId 或 region 直接抛 {@link com.amz.connector.UnknownMarketplaceException}，
     * 不再静默回落默认区域。
     */
    private final HttpTransport httpTransport;
    private final LwaTokenManager lwaTokenManager;
    private final ShopCredentialStore shopCredentialStore;
    private final SpApiRequestFactory requestFactory;

    /**
     * SP-API 通用限流器：按 (shopId, endpoint) 维度滑动窗口，并支持根据
     * x-amzn-RateLimit-Limit 响应头动态收紧窗口上限。
     */
    private final SpiRateLimiter spiRateLimiter;

    /**
     * Micrometer 指标注册表（软依赖，未配置时退化为无指标）。
     */
    private final ObjectProvider<MeterRegistry> meterRegistryProvider;

    public OrdersClient(HttpTransport httpTransport,
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
     * Orders 端点标识，用于限流维度与动态调整。
     */
    private static final String ORDERS_ENDPOINT = "orders";

    /**
     * 订单行级接口路径前缀（/orders/v0/orders/{orderId}/orderItems）。
     */
    private static final String ORDER_ITEMS_PATH = "/orders/v0/orders/";

    /**
     * 拉取单个订单的行级明细（sellerSku / 数量 / 行金额）。
     * <p>
     * 供订单同步调度器为<b>新订单</b>补全利润核算所需的真实 SKU
     * （列表接口不含行级数据，旧实现以 amazonOrderId 冒充 SKU 导致 COGS 恒缺失）。
     * 复用 orders 端点限流桶；官方配额较紧，调用方应仅对新订单调用。
     *
     * @param shopId        店铺 ID
     * @param marketplaceId Marketplace ID
     * @param amazonOrderId Amazon 订单号
     * @return payload.OrderItems 数组中的行对象列表；接口异常时返回空列表（由调用方降级）
     */
    public List<JsonObject> fetchOrderItems(Long shopId, String marketplaceId, String amazonOrderId) {
        ShopCredential credential = shopCredentialStore.get(shopId);
        if (credential == null) {
            throw new IllegalArgumentException("No credential found for shopId=" + shopId);
        }
        String region = MarketplaceRegistry.resolveRegion(marketplaceId);
        String endpoint = MarketplaceRegistry.resolveEndpointForRegion(region);
        String host = MarketplaceRegistry.resolveHost(region);
        // 分组码 NA/EU/FE 不是 AWS region：SigV4 作用域必须用真实 region（P0-48）
        String awsRegion = MarketplaceRegistry.resolveAwsRegion(region);

        String accessToken = lwaTokenManager.getToken(credential);

        spiRateLimiter.acquire(shopId, ORDERS_ENDPOINT);

        String path = ORDER_ITEMS_PATH + amazonOrderId + "/orderItems";
        HttpRequest request = requestFactory.spApi(
                "GET", endpoint, host, awsRegion, path, null, null,
                accessToken, credential.getAccessKey(), credential.getSecretKey());

        try {
            HttpResponse<String> response = sendWithRetry(request, ORDERS_ENDPOINT);
            if (response == null || response.statusCode() != 200) {
                log.warn("fetchOrderItems failed orderId={} status={}",
                        amazonOrderId, response == null ? -1 : response.statusCode());
                return new ArrayList<>();
            }
            JsonObject body = JsonParser.parseString(response.body()).getAsJsonObject();
            JsonObject payload = body.has("payload") && body.get("payload").isJsonObject()
                    ? body.getAsJsonObject("payload") : body;
            List<JsonObject> items = new ArrayList<>();
            if (payload.has("OrderItems") && payload.get("OrderItems").isJsonArray()) {
                for (JsonElement e : payload.getAsJsonArray("OrderItems")) {
                    items.add(e.getAsJsonObject());
                }
            }
            return items;
        } catch (Exception e) {
            // 行级明细拉取失败不应阻断订单主流程：返回空列表由调度器降级为订单级消息
            log.warn("fetchOrderItems error orderId={}: {}", amazonOrderId, e.getMessage());
            return new ArrayList<>();
        }
    }

    /**
     * 拉取指定店铺在某 marketplace 下、createdAfter 之后的订单列表。
     *
     * @param shopId        店铺 ID
     * @param marketplaceId Amazon Marketplace ID
     * @param createdAfter  订单创建时间下限
     * @param orderStatuses 订单状态过滤（如 Shipped/Unshipped），可为空
     * @return 订单原始 JSON 列表（每条为 payload.Orders 数组中的一个对象）
     */
    @SentinelResource(value = "fetchOrders", fallback = "fetchOrdersFallback")
    public List<JsonObject> fetchOrders(Long shopId, String marketplaceId,
                                        Instant createdAfter, List<String> orderStatuses) {
        ShopCredential credential = shopCredentialStore.get(shopId);
        if (credential == null) {
            throw new IllegalArgumentException("No credential found for shopId=" + shopId);
        }

        String region = MarketplaceRegistry.resolveRegion(marketplaceId);
        String endpoint = MarketplaceRegistry.resolveEndpointForRegion(region);
        String host = MarketplaceRegistry.resolveHost(region);
        // 分组码 NA/EU/FE 不是 AWS region：SigV4 作用域必须用真实 region（P0-48）
        String awsRegion = MarketplaceRegistry.resolveAwsRegion(region);

        String accessToken = lwaTokenManager.getToken(credential);

        List<JsonObject> allOrders = new ArrayList<>();
        String nextToken = null;
        int pageCount = 0;

        do {
            // 主动限流：按 (shopId, orders) 维度滑动窗口阻塞等待，避免触发 429
            spiRateLimiter.acquire(shopId, ORDERS_ENDPOINT);

            TreeMap<String, String> params = new TreeMap<>();
            params.put("CreatedAfter", ISO_INSTANT.format(createdAfter));
            params.put("MarketplaceIds", marketplaceId);
            if (orderStatuses != null && !orderStatuses.isEmpty()) {
                params.put("OrderStatuses", String.join(",", orderStatuses));
            }
            if (nextToken != null) {
                params.put("NextToken", nextToken);
            }
            String queryString = buildCanonicalQueryString(params);

            HttpRequest request = requestFactory.spApi(
                    "GET", endpoint, host, awsRegion, ORDERS_PATH, queryString, null,
                    accessToken, credential.getAccessKey(), credential.getSecretKey());

            HttpResponse<String> response = sendWithRetry(request, ORDERS_ENDPOINT);
            if (response == null || response.statusCode() != 200) {
                int status = response == null ? -1 : response.statusCode();
                // 401/403：access_token 失效或被吊销，主动驱逐缓存，
                // 下次调用将重新走 LWA 刷新，避免在剩余 TTL 内持续 401
                if (status == 401 || status == 403) {
                    lwaTokenManager.invalidate(credential);
                    log.warn("fetchOrders got {} — LWA token cache invalidated shopId={}", status, shopId);
                }
                throw new RuntimeException("fetchOrders failed shopId=" + shopId
                        + " status=" + status
                        + " body=" + (response == null ? "" : response.body()));
            }

            JsonObject body = JsonParser.parseString(response.body()).getAsJsonObject();
            JsonObject payload = body.has("payload") && body.get("payload").isJsonObject()
                    ? body.getAsJsonObject("payload") : body;
            if (payload.has("Orders") && payload.get("Orders").isJsonArray()) {
                for (JsonElement e : payload.getAsJsonArray("Orders")) {
                    allOrders.add(e.getAsJsonObject());
                }
            }
            nextToken = (payload.has("NextToken") && !payload.get("NextToken").isJsonNull())
                    ? payload.get("NextToken").getAsString() : null;
            pageCount++;
            log.debug("fetchOrders shopId={} page={} collected={} hasNext={}",
                    shopId, pageCount, allOrders.size(), nextToken != null);
        } while (nextToken != null);

        log.info("fetchOrders done shopId={} pages={} total={}", shopId, pageCount, allOrders.size());
        return allOrders;
    }
    /**
     * fetchOrders 的 fallback 方法：原方法抛出异常或被熔断时返回空列表，避免调用方整体失败。
     */
    public List<JsonObject> fetchOrdersFallback(Long shopId, String marketplaceId,
                                                Instant createdAfter, List<String> orderStatuses,
                                                Throwable e) {
        // 不返回空列表伪装成功：降级 / 异常时抛出异常，由上游记录失败并告警
        throw new RuntimeException("fetchOrders degraded (circuit-breaker/exception) shopId="
                + shopId + " marketplaceId=" + marketplaceId, e);
    }

    /**
     * 发送请求，遇到 429 限流或 5xx 服务端错误时按指数退避重试。
     * <p>
     * 429 时读取 {@code x-amzn-RateLimit-Limit} 响应头，通过 {@link SpiRateLimiter#updateLimit}
     * 动态收紧本地滑动窗口上限，使后续请求与 Amazon 实际配额对齐。
     *
     * @param request  已构建好的 HTTP 请求
     * @param endpoint 端点标识（如 {@code "orders"}），用于限流维度动态调整
     */
    private HttpResponse<String> sendWithRetry(HttpRequest request, String endpoint) {
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
            int code = response.statusCode();
            if (code == 429) {
                recordThrottle(ORDERS_ENDPOINT);
                // 读取 x-amzn-RateLimit-Limit 响应头（req/s），动态收紧本地窗口
                String rateLimitHeader = response.headers()
                        .firstValue("x-amzn-RateLimit-Limit").orElse(null);
                if (rateLimitHeader != null && !rateLimitHeader.isBlank()) {
                    spiRateLimiter.updateLimit(endpoint, rateLimitHeader);
                }
                long backoff = (1L << attempt) * 1000L;
                log.warn("Rate limited (429), limit={} retrying after {}ms attempt={}",
                        rateLimitHeader, backoff, attempt);
                sleep(backoff);
                continue;
            }
            if (code >= 500 && code < 600) {
                long backoff = (1L << attempt) * 1000L;
                log.warn("Server error {} retrying after {}ms attempt={}", code, backoff, attempt);
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
     * @param endpoint SP-API 资源标识（如 {@code "orders"}）
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
}
