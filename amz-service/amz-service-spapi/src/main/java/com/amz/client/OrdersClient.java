package com.amz.client;

import com.amz.auth.LwaTokenManager;
import com.amz.credential.ShopCredential;
import com.amz.credential.ShopCredentialStore;
import com.amz.connector.HttpTransport;
import com.amz.connector.LocalApiException;
import com.amz.connector.MarketplaceRegistry;
import com.amz.connector.SpApiCallException;
import com.amz.connector.SpApiEndpointResolver;
import com.amz.connector.RestrictedResource;
import com.amz.connector.SpApiRequestFactory;
import com.amz.ratelimit.SpiRateLimiter;
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
    private final SpApiGateway gateway;

    public OrdersClient(HttpTransport httpTransport,
                        LwaTokenManager lwaTokenManager,
                        ShopCredentialStore shopCredentialStore,
                        SpiRateLimiter spiRateLimiter,
                        SpApiRequestFactory requestFactory,
                        ObjectProvider<MeterRegistry> meterRegistryProvider) {
        this(httpTransport, lwaTokenManager, shopCredentialStore, spiRateLimiter,
                requestFactory, meterRegistryProvider,
                new SpApiGateway(httpTransport, lwaTokenManager, shopCredentialStore,
                        spiRateLimiter, requestFactory, meterRegistryProvider));
    }

    @Autowired
    public OrdersClient(HttpTransport httpTransport,
                        LwaTokenManager lwaTokenManager,
                        ShopCredentialStore shopCredentialStore,
                        SpiRateLimiter spiRateLimiter,
                        SpApiRequestFactory requestFactory,
                        ObjectProvider<MeterRegistry> meterRegistryProvider,
                        SpApiGateway gateway) {
        this.httpTransport = httpTransport;
        this.lwaTokenManager = lwaTokenManager;
        this.shopCredentialStore = shopCredentialStore;
        this.spiRateLimiter = spiRateLimiter;
        this.requestFactory = requestFactory;
        this.meterRegistryProvider = meterRegistryProvider;
        this.gateway = gateway;
    }

    /**
     * 官方 operationId（限流维度；数值见 contracts/ordersV0.json 的 Usage Plan 表）。
     * <p>
     * 旧实现用粗粒度 {@code "orders"}（命中兜底 30 req/30s ≈ 1 req/s），对官方
     * 0.0167 req/s 的 {@code getOrders} 越权约 60 倍——分页稍多就会撞 429。
     */
    private static final String OP_GET_ORDERS = "orders.getOrders";

    /** {@code orders.getOrderItems}：官方 0.5 req/s、burst 30（旧实现同样走 1 req/s 兜底）。 */
    private static final String OP_GET_ORDER_ITEMS = "orders.getOrderItems";

    /**
     * 订单行级接口路径前缀（/orders/v0/orders/{orderId}/orderItems）。
     */
    private static final String ORDER_ITEMS_PATH = "/orders/v0/orders/";

    /**
     * 拉取单个订单的行级明细（sellerSku / 数量 / 行金额）。
     * <p>
     * 供订单同步调度器为<b>新订单</b>补全利润核算所需的真实 SKU
     * （列表接口不含行级数据，旧实现以 amazonOrderId 冒充 SKU 导致 COGS 恒缺失）。
     * 限流走独立令牌桶 {@code orders.getOrderItems}（官方 0.5 req/s、burst 30），
     * 与 {@code orders.getOrders}（0.0167 req/s）互不挤占；调用方应仅对新订单调用。
     *
     * @param shopId        店铺 ID
     * @param marketplaceId Marketplace ID
     * @param amazonOrderId Amazon 订单号
     * @return payload.OrderItems 数组中的行对象列表；接口失败时抛出类型化异常，绝不伪装成空列表
     */
    public List<JsonObject> fetchOrderItems(Long shopId, String marketplaceId, String amazonOrderId) {
        SpApiGateway.ResolvedShop shop = gateway.resolveShop(shopId, marketplaceId);
        String path = ORDER_ITEMS_PATH + amazonOrderId + "/orderItems";
        JsonObject body = gateway.callJsonWithStatuses(
                "GET", shop, OP_GET_ORDER_ITEMS, path, null, null, 200);
        JsonObject payload = body.has("payload") && body.get("payload").isJsonObject()
                ? body.getAsJsonObject("payload") : body;
        List<JsonObject> items = new ArrayList<>();
        if (payload.has("OrderItems") && payload.get("OrderItems").isJsonArray()) {
            for (JsonElement e : payload.getAsJsonArray("OrderItems")) {
                items.add(e.getAsJsonObject());
            }
        }
        return items;
    }

    /**
     * 拉取包含买家信息与配送地址的订单列表，所有业务请求均使用按精确资源范围申请的 RDT。
     * 受限令牌获取失败时直接失败，绝不回退 LWA，避免请求未授权的 PII。
     */
    public List<JsonObject> fetchOrdersWithRestrictedData(Long shopId, String marketplaceId,
                                                          Instant createdAfter,
                                                          List<String> orderStatuses) {
        SpApiGateway.ResolvedShop shop = gateway.resolveShop(shopId, marketplaceId);
        List<RestrictedResource> resources = List.of(new RestrictedResource(
                "GET", ORDERS_PATH, List.of("buyerInfo", "shippingAddress")));

        List<JsonObject> allOrders = new ArrayList<>();
        String nextToken = null;
        int pageCount = 0;

        do {
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

            JsonObject body = gateway.callJsonWithRestrictedData(
                    "GET", shop, OP_GET_ORDERS, ORDERS_PATH, queryString, null,
                    resources, null, 200);
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
            log.debug("fetchOrdersWithRestrictedData shopId={} page={} collected={} hasNext={}",
                    shopId, pageCount, allOrders.size(), nextToken != null);
        } while (nextToken != null);

        log.info("fetchOrdersWithRestrictedData done shopId={} pages={} total={}",
                shopId, pageCount, allOrders.size());
        return allOrders;
    }

    /**
     * 拉取单个订单的买家字段，RDT 路径必须与实际业务请求路径完全一致。
     */
    public List<JsonObject> fetchOrderItemsWithRestrictedData(Long shopId, String marketplaceId,
                                                              String amazonOrderId) {
        SpApiGateway.ResolvedShop shop = gateway.resolveShop(shopId, marketplaceId);
        String path = ORDER_ITEMS_PATH + amazonOrderId + "/orderItems";
        List<RestrictedResource> resources = List.of(
                new RestrictedResource("GET", path, List.of("buyerInfo")));
        JsonObject body = gateway.callJsonWithRestrictedData(
                "GET", shop, OP_GET_ORDER_ITEMS, path, null, null, resources, null, 200);
        JsonObject payload = body.has("payload") && body.get("payload").isJsonObject()
                ? body.getAsJsonObject("payload") : body;
        List<JsonObject> items = new ArrayList<>();
        if (payload.has("OrderItems") && payload.get("OrderItems").isJsonArray()) {
            for (JsonElement e : payload.getAsJsonArray("OrderItems")) {
                items.add(e.getAsJsonObject());
            }
        }
        return items;
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
        SpApiGateway.ResolvedShop shop = gateway.resolveShop(shopId, marketplaceId);

        List<JsonObject> allOrders = new ArrayList<>();
        String nextToken = null;
        int pageCount = 0;

        do {
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

            JsonObject body = gateway.callJsonWithStatuses(
                    "GET", shop, OP_GET_ORDERS, ORDERS_PATH, queryString, null, 200);
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
     * 收紧**该店铺该 operation** 的令牌桶速率（观测值回升时自动恢复官方默认上限）。
     *
     * @param request     已构建好的 HTTP 请求
     * @param shopId      店铺 ID（限流按店铺隔离，避免单店被限流拖慢其它店铺）
     * @param operationId 官方 operationId（如 {@code "orders.getOrders"}），用于限流维度动态调整
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
            int code = response.statusCode();
            if (code == 429) {
                recordThrottle(operationId);
                // 读取 x-amzn-RateLimit-Limit 响应头（req/s），收紧本店铺该 operation 的速率
                String rateLimitHeader = response.headers()
                        .firstValue("x-amzn-RateLimit-Limit").orElse(null);
                if (rateLimitHeader != null && !rateLimitHeader.isBlank()) {
                    spiRateLimiter.updateLimit(shopId, operationId, rateLimitHeader);
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
     * @param operationId 官方 operationId（如 {@code "orders.getOrders"}）；
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
