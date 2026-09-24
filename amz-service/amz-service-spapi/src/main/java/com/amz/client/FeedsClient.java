package com.amz.client;

import com.amz.auth.LwaTokenManager;
import com.amz.credential.ShopCredential;
import com.amz.credential.ShopCredentialStore;
import com.amz.connector.HttpTransport;
import com.amz.connector.MarketplaceRegistry;
import com.amz.connector.SpApiEndpointResolver;
import com.amz.connector.SpApiRequestFactory;
import com.amz.ratelimit.SpiRateLimiter;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Amazon SP-API Feeds v2021-06-30 真实客户端。
 * <p>
 * 鉴权与容错模式与 {@link FbaInventoryClient} 保持一致：LWA Token（LwaTokenManager）
 * + 统一请求构造（SpApiRequestFactory：必填 user-agent、条件签名、预签名 URL 不携带凭证）
 * + 429 指数退避重试（sendWithRetry）。
 * 区域（NA/EU/FE）到 SP-API 端点与签名区域的映射也与 FbaInventoryClient 一致。
 * <p>
 * 完整实现 Listing Feed 提交流程：
 * <ol>
 *   <li>POST /feeds/2021-06-30/documents 创建 Feed 文档，获取 feedDocumentId 与 S3 预签名上传地址</li>
 *   <li>PUT 将 Feed 内容（JSON）上传到 S3 预签名地址</li>
 *   <li>POST /feeds/2021-06-30/feeds 提交 Feed（引用 inputFeedDocumentId），获取 feedId</li>
 *   <li>GET /feeds/2021-06-30/feeds/{feedId} 查询处理状态（processingStatus）</li>
 * </ol>
 * <p>
 * 注意：content 必须为符合 SP-API JSON Listings Feed 规范的内容
 * （见 https://developer-docs.amazon.com/sp-api/docs/feeds-api-v2021-06-30-use-case-guide）。
 * 本客户端只负责真实调用，不对业务 payload 的格式做校验。
 */
@Component
public class FeedsClient {

    private static final Logger log = LoggerFactory.getLogger(FeedsClient.class);

    private static final String FEEDS_PATH = "/feeds/2021-06-30/feeds";
    private static final String DOCUMENTS_PATH = "/feeds/2021-06-30/documents";

    /** Listing 数据使用的 Feed 类型与内容类型。 */
    private static final String FEED_TYPE = "JSON_LISTINGS_FEED";
    private static final String CONTENT_TYPE = "application/json";

    /** Feed 端点标识，用于限流指标维度。 */
    private static final String FEEDS_ENDPOINT = "feeds";

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
     * Micrometer 指标注册表（软依赖，未配置时退化为无指标）。
     */
    private final ObjectProvider<MeterRegistry> meterRegistryProvider;

    /**
     * 统一滑动窗口限流器（feeds 端点默认 30 req/30s 兜底，按店铺维度隔离）。
     * SP-API Feeds 官方配额较紧（createFeed 约 0.0083 req/s），此处保守前置限流。
     */
    private final SpiRateLimiter spiRateLimiter;

    public FeedsClient(HttpTransport httpTransport,
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
     * 提交 Listing Feed，返回 SP-API feedId。
     *
     * @param shopId        店铺 ID
     * @param marketplaceId 目标 Marketplace ID
     * @param content       符合 SP-API Feed 规范的 JSON 内容
     * @return SP-API feedId
     */
    public String submitFeed(Long shopId, String marketplaceId, String content) {
        ResolvedShop shop = resolveShop(shopId, marketplaceId);
        String accessToken = lwaTokenManager.getToken(shop.credential);

        // 前置滑动窗口限流（按 shopId + feeds 端点维度）
        spiRateLimiter.acquire(shopId, FEEDS_ENDPOINT);

        // 1. 创建 Feed 文档
        JsonObject doc = createFeedDocument(shop, accessToken, CONTENT_TYPE);
        String feedDocumentId = doc.get("feedDocumentId").getAsString();
        String uploadUrl = doc.get("url").getAsString();
        log.info("FeedsClient.createFeedDocument shopId={} feedDocumentId={}", shopId, feedDocumentId);

        // 2. 上传内容到 S3 预签名地址
        uploadDocument(uploadUrl, CONTENT_TYPE, content);

        // 3. 创建 Feed，获取 feedId
        JsonObject feed = createFeed(shop, accessToken, FEED_TYPE, marketplaceId, feedDocumentId);
        String feedId = feed.get("feedId").getAsString();
        log.info("FeedsClient.submitFeed done shopId={} marketplaceId={} feedId={}", shopId, marketplaceId, feedId);
        return feedId;
    }

    /**
     * 查询 Feed 处理状态，返回 SP-API 原始 JSON（含 processingStatus 等字段）。
     *
     * @param shopId  店铺 ID
     * @param feedId  SP-API feedId
     * @return SP-API 返回的 Feed 状态 JSON
     */
    public JsonObject getFeedStatus(Long shopId, String feedId) {
        ResolvedShop shop = resolveShop(shopId, null);
        String accessToken = lwaTokenManager.getToken(shop.credential);

        String path = FEEDS_PATH + "/" + feedId;
        HttpRequest request = requestFactory.spApi(
                "GET", shop.endpoint, shop.host, shop.awsRegion, path, null, null,
                accessToken, shop.credential.getAccessKey(), shop.credential.getSecretKey());

        HttpResponse<String> response = sendWithRetry(request);
        if (response == null || response.statusCode() != 200) {
            int status = response == null ? -1 : response.statusCode();
            // 401/403：access_token 失效，主动驱逐 LWA 缓存（与 OrdersClient 对齐）
            if (status == 401 || status == 403) {
                lwaTokenManager.invalidate(shop.credential);
                log.warn("getFeedStatus got {} — LWA token cache invalidated shopId={}", status, shopId);
            }
            throw new RuntimeException("getFeedStatus failed feedId=" + feedId
                    + " status=" + status
                    + " body=" + (response == null ? "" : response.body()));
        }
        JsonObject body = JsonParser.parseString(response.body()).getAsJsonObject();
        log.debug("FeedsClient.getFeedStatus feedId={} processingStatus={}",
                feedId, body.has("processingStatus") ? body.get("processingStatus").getAsString() : "?");
        return body;
    }

    /**
     * 解析店铺凭证与 SP-API 端点/区域信息（P0-36 fail-closed）。
     * 优先用显式 marketplaceId 映射区域；缺失时回退到凭证登记的 marketplaceId，再回退到凭证区域；
     * 三者都缺失或非法时**抛异常**，不再回落默认区域。
     */
    private ResolvedShop resolveShop(Long shopId, String marketplaceId) {
        ShopCredential credential = shopCredentialStore.get(shopId);
        if (credential == null) {
            throw new IllegalArgumentException("No credential found for shopId=" + shopId);
        }
        // fail-closed（P0-36）：显式 marketplaceId → 凭证 marketplaceId → 凭证 region → 抛异常。
        // 旧实现在三者全空时静默回落 NA 端点，会把请求发到未经确认的区域。
        String region = MarketplaceRegistry.resolveRegion(
                marketplaceId, credential.getMarketplaceId(), credential.getRegion(), "shopId=" + shopId);
        // P0-51：端点解析统一走 requestFactory（官方主机或非生产覆盖，与出站主机校验同源）
        SpApiEndpointResolver.Endpoint ep = requestFactory.resolveEndpoint(region);
        String endpoint = ep.baseUrl();
        String host = ep.host();
        // 分组码 NA/EU/FE 不是 AWS region：SigV4 作用域必须用真实 region（P0-48）
        String awsRegion = MarketplaceRegistry.resolveAwsRegion(region);
        return new ResolvedShop(credential, region, endpoint, host, awsRegion);
    }

    /**
     * 创建 Feed 文档（POST /feeds/2021-06-30/documents），返回 feedDocumentId 与 S3 预签名上传地址。
     */
    private JsonObject createFeedDocument(ResolvedShop shop, String accessToken, String contentType) {
        JsonObject req = new JsonObject();
        req.addProperty("contentType", contentType);
        String body = req.toString();

        HttpRequest request = requestFactory.spApi(
                "POST", shop.endpoint, shop.host, shop.awsRegion, DOCUMENTS_PATH, null, body,
                accessToken, shop.credential.getAccessKey(), shop.credential.getSecretKey());

        HttpResponse<String> response = sendWithRetry(request);
        if (response == null || (response.statusCode() != 200 && response.statusCode() != 201)) {
            throw new RuntimeException("createFeedDocument failed status="
                    + (response == null ? -1 : response.statusCode())
                    + " body=" + (response == null ? "" : response.body()));
        }
        return JsonParser.parseString(response.body()).getAsJsonObject();
    }

    /**
     * 将 Feed 内容 PUT 上传到 S3 预签名地址（无需额外 AWS 签名，预签名 URL 已携带鉴权参数）。
     */
    private void uploadDocument(String uploadUrl, String contentType, String content) {
        // 预签名 URL 自带鉴权参数：绝不注入 LWA token / AWS 签名（P0-50）
        HttpRequest request = requestFactory.presigned("PUT", uploadUrl, contentType, content,
                Duration.ofSeconds(30));
        try {
            HttpResponse<String> response = httpTransport.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) {
                throw new RuntimeException("uploadDocument failed status=" + response.statusCode()
                        + " body=" + response.body());
            }
            log.debug("uploadDocument success");
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("uploadDocument error: " + e.getMessage(), e);
        }
    }

    /**
     * 创建 Feed（POST /feeds/2021-06-30/feeds），引用 inputFeedDocumentId，返回 feedId。
     */
    private JsonObject createFeed(ResolvedShop shop, String accessToken, String feedType,
                                 String marketplaceId, String feedDocumentId) {
        JsonObject req = new JsonObject();
        req.addProperty("feedType", feedType);
        JsonArray mks = new JsonArray();
        mks.add(marketplaceId);
        req.add("marketplaceIds", mks);
        req.addProperty("inputFeedDocumentId", feedDocumentId);
        String body = req.toString();

        HttpRequest request = requestFactory.spApi(
                "POST", shop.endpoint, shop.host, shop.awsRegion, FEEDS_PATH, null, body,
                accessToken, shop.credential.getAccessKey(), shop.credential.getSecretKey());

        HttpResponse<String> response = sendWithRetry(request);
        if (response == null || (response.statusCode() != 200 && response.statusCode() != 202)) {
            throw new RuntimeException("createFeed failed status="
                    + (response == null ? -1 : response.statusCode())
                    + " body=" + (response == null ? "" : response.body()));
        }
        return JsonParser.parseString(response.body()).getAsJsonObject();
    }

    /**
     * 发送 HTTP 请求，遇到 429 限流时按指数退避重试。
     */
    private HttpResponse<String> sendWithRetry(HttpRequest request) {
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
                recordThrottle(FEEDS_ENDPOINT);
                // 读取 x-amzn-RateLimit-Limit（req/s），收紧本地窗口
                String rateLimitHeader = response.headers()
                        .firstValue("x-amzn-RateLimit-Limit").orElse(null);
                if (rateLimitHeader != null && !rateLimitHeader.isBlank()) {
                    spiRateLimiter.updateLimit(FEEDS_ENDPOINT, rateLimitHeader);
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
     * @param endpoint SP-API 资源标识（如 {@code "feeds"}）
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
     * 店铺解析结果：凭证 + 分组区域码 + 端点 + 主机名 + AWS region。
     * <p>
     * {@code region} 是分组码（NA/EU/FE，决定端点主机），{@code awsRegion} 参与 SigV4 作用域。
     */
    private static final class ResolvedShop {
        final ShopCredential credential;
        final String region;
        final String endpoint;
        final String host;
        final String awsRegion;

        ResolvedShop(ShopCredential credential, String region, String endpoint, String host,
                     String awsRegion) {
            this.credential = credential;
            this.region = region;
            this.endpoint = endpoint;
            this.host = host;
            this.awsRegion = awsRegion;
        }
    }
}
