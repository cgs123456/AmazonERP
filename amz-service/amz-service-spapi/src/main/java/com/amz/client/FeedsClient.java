package com.amz.client;

import com.amz.auth.LwaTokenManager;
import com.amz.credential.ShopCredential;
import com.amz.credential.ShopCredentialStore;
import com.amz.connector.ErrorSummary;
import com.amz.connector.HttpTransport;
import com.amz.connector.LocalApiException;
import com.amz.connector.MarketplaceRegistry;
import com.amz.connector.SpApiCallException;
import com.amz.connector.SpApiEndpointResolver;
import com.amz.connector.SpApiRequestFactory;
import com.amz.model.FeedIssue;
import com.amz.model.FeedResult;
import com.amz.ratelimit.SpiRateLimiter;
import com.amz.service.FeedResultErrorStore;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;
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

    /**
     * 官方 operationId（限流维度；数值见 contracts/feeds_2021-06-30.json 的 Usage Plan 表）。
     * <p>
     * 旧实现三个 operation 共用一个粗粒度 {@code "feeds"} 桶（命中兜底 1 req/s、
     * burst 30）：对 0.0083 req/s 的 {@code createFeed} 越权约 120 倍，而
     * {@code createFeedDocument}（0.5 req/s）反被拖慢——两种偏差同时存在。
     */
    private static final String OP_CREATE_FEED = "feeds.createFeed";
    private static final String OP_CREATE_FEED_DOCUMENT = "feeds.createFeedDocument";
    private static final String OP_GET_FEED = "feeds.getFeed";
    private static final String OP_GET_FEED_DOCUMENT = "feeds.getFeedDocument";

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
    private final SpApiGateway gateway;
    private final FeedResultErrorStore feedResultErrorStore;

    public FeedsClient(HttpTransport httpTransport,
                       LwaTokenManager lwaTokenManager,
                       ShopCredentialStore shopCredentialStore,
                       SpiRateLimiter spiRateLimiter,
                       SpApiRequestFactory requestFactory,
                       ObjectProvider<MeterRegistry> meterRegistryProvider) {
        this(httpTransport, lwaTokenManager, shopCredentialStore, spiRateLimiter,
                requestFactory, meterRegistryProvider,
                new SpApiGateway(httpTransport, lwaTokenManager, shopCredentialStore,
                        spiRateLimiter, requestFactory, meterRegistryProvider), null);
    }

    @Autowired
    public FeedsClient(HttpTransport httpTransport,
                       LwaTokenManager lwaTokenManager,
                       ShopCredentialStore shopCredentialStore,
                       SpiRateLimiter spiRateLimiter,
                       SpApiRequestFactory requestFactory,
                       ObjectProvider<MeterRegistry> meterRegistryProvider,
                       SpApiGateway gateway,
                       FeedResultErrorStore feedResultErrorStore) {
        this.httpTransport = httpTransport;
        this.lwaTokenManager = lwaTokenManager;
        this.shopCredentialStore = shopCredentialStore;
        this.spiRateLimiter = spiRateLimiter;
        this.requestFactory = requestFactory;
        this.meterRegistryProvider = meterRegistryProvider;
        this.gateway = gateway;
        this.feedResultErrorStore = feedResultErrorStore;
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
        SpApiGateway.ResolvedShop shop = gateway.resolveShop(shopId, marketplaceId);

        // 限流按各自官方 operationId 逐步取令牌，不再在入口按粗粒度 "feeds" 取一次：
        //   ① createFeedDocument → feeds.createFeedDocument（0.5 req/s、burst 15）
        //   ② createFeed         → feeds.createFeed|JSON_LISTINGS_FEED（见 OFFICIAL_PLANS）
        // 中间的 S3 预签名 PUT 不是 SP-API 调用，不消耗任何令牌。

        // 1. 创建 Feed 文档
        JsonObject doc = createFeedDocument(shop, CONTENT_TYPE);
        String feedDocumentId = doc.get("feedDocumentId").getAsString();
        String uploadUrl = doc.get("url").getAsString();
        log.info("FeedsClient.createFeedDocument shopId={} feedDocumentId={}", shopId, feedDocumentId);

        // 2. 上传内容到 S3 预签名地址
        uploadDocument(uploadUrl, CONTENT_TYPE, content);

        // 3. 创建 Feed，获取 feedId
        JsonObject feed = createFeed(shop, FEED_TYPE, marketplaceId, feedDocumentId);
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
        SpApiGateway.ResolvedShop shop = gateway.resolveShop(shopId, null);
        String path = FEEDS_PATH + "/" + feedId;
        JsonObject body = gateway.callJsonWithStatuses(
                "GET", shop, OP_GET_FEED, path, null, null, 200);
        log.debug("FeedsClient.getFeedStatus feedId={} processingStatus={}",
                feedId, body.has("processingStatus") ? body.get("processingStatus").getAsString() : "?");
        return body;
    }

    /**
     * 下载并解析 Feed processing report。
     * <p>
     * 调用顺序遵循官方 Feeds 用例指南：getFeed → resultFeedDocumentId →
     * getFeedDocument → 预签名 URL 下载 → 可选 GZIP 解压 → JSON processing report 解析。
     * 任何一步缺失或格式不合法都显式失败，绝不返回空结果冒充成功。
     *
     * @param shopId 店铺 ID
     * @param feedId SP-API feedId
     * @return 结构化处理结果
     */
    public FeedResult fetchFeedResult(Long shopId, String feedId) {
        if (shopId == null || feedId == null || feedId.isBlank()) {
            throw LocalApiException.of(LocalApiException.CODE_INVALID_REQUEST,
                    "shopId and feedId are required");
        }
        SpApiGateway.ResolvedShop shop = gateway.resolveShop(shopId, null);
        JsonObject feed = getFeedStatus(shopId, feedId);
        String processingStatus = optionalString(feed, "processingStatus");
        if (!"DONE".equals(processingStatus)) {
            throw LocalApiException.of(LocalApiException.CODE_INVALID_REQUEST,
                    "Feed result is unavailable until processingStatus=DONE, current="
                            + (processingStatus == null ? "UNKNOWN" : processingStatus));
        }

        String resultDocumentId = requireString(feed, "resultFeedDocumentId", "Feed");
        String metadataPath = DOCUMENTS_PATH + "/" + resultDocumentId;
        JsonObject document = gateway.callJsonWithStatus(
                "GET", shop, OP_GET_FEED_DOCUMENT, metadataPath, null, null, 200);
        String downloadUrl = requireString(document, "url", "FeedDocument");
        String compression = optionalString(document, "compressionAlgorithm");

        byte[] downloaded = gateway.downloadBytes(downloadUrl);
        byte[] reportBytes = decompress(downloaded, compression);
        String report = new String(reportBytes, StandardCharsets.UTF_8);
        FeedResult result = parseProcessingReport(feedId, resultDocumentId, report);
        if (feedResultErrorStore != null) {
            feedResultErrorStore.replace(shopId, result);
        }
        log.info("FeedsClient.fetchFeedResult feedId={} processed={} accepted={} invalid={} errors={}",
                feedId, result.getMessagesProcessed(), result.getMessagesAccepted(),
                result.getMessagesInvalid(), result.getErrors());
        return result;
    }

    private FeedResult parseProcessingReport(String feedId, String resultDocumentId, String report) {
        JsonObject root;
        try {
            root = JsonParser.parseString(report).getAsJsonObject();
        } catch (RuntimeException e) {
            throw LocalApiException.of(LocalApiException.CODE_INVALID_REQUEST,
                    "Feed processing report is not a JSON object");
        }

        if (root.has("header") && root.get("header").isJsonObject()) {
            String reportFeedId = optionalString(root.getAsJsonObject("header"), "feedId");
            if (reportFeedId != null && !reportFeedId.isBlank() && !feedId.equals(reportFeedId)) {
                throw LocalApiException.of(LocalApiException.CODE_INVALID_REQUEST,
                        "Feed processing report feedId mismatch");
            }
        }

        List<FeedIssue> issues = new ArrayList<>();
        if (root.has("issues") && root.get("issues").isJsonArray()) {
            JsonArray items = root.getAsJsonArray("issues");
            for (int i = 0; i < items.size(); i++) {
                if (!items.get(i).isJsonObject()) {
                    continue;
                }
                JsonObject item = items.get(i).getAsJsonObject();
                int rowIndex = item.has("messageId") && item.get("messageId").isJsonPrimitive()
                        ? item.get("messageId").getAsInt() : i + 1;
                String severity = optionalString(item, "severity");
                issues.add(new FeedIssue(
                        rowIndex,
                        optionalString(item, "sku"),
                        optionalString(item, "code"),
                        severity,
                        truncate(optionalString(item, "message"), 1000)));
            }
        }

        int processed = -1;
        int accepted = -1;
        int invalid = -1;
        int errors = issues.stream().mapToInt(issue -> issue.isError() ? 1 : 0).sum();
        int warnings = issues.stream().mapToInt(issue -> issue.isWarning() ? 1 : 0).sum();
        if (root.has("summary") && root.get("summary").isJsonObject()) {
            JsonObject summary = root.getAsJsonObject("summary");
            processed = intOrDefault(summary, "messagesProcessed", -1);
            accepted = intOrDefault(summary, "messagesAccepted", -1);
            invalid = intOrDefault(summary, "messagesInvalid", -1);
            errors = intOrDefault(summary, "errors", errors);
            warnings = intOrDefault(summary, "warnings", warnings);
        }

        if (processed < 0 && accepted < 0 && invalid < 0 && issues.isEmpty()) {
            throw LocalApiException.of(LocalApiException.CODE_INVALID_REQUEST,
                    "Feed processing report has neither summary nor issues");
        }
        return new FeedResult(feedId, resultDocumentId, processed, accepted, invalid,
                errors, warnings, issues);
    }

    private byte[] decompress(byte[] bytes, String compressionAlgorithm) {
        if (bytes == null) {
            throw LocalApiException.of(LocalApiException.CODE_INVALID_REQUEST,
                    "Feed processing report download returned no bytes");
        }
        if (compressionAlgorithm == null || compressionAlgorithm.isBlank()) {
            return bytes;
        }
        if (!"GZIP".equalsIgnoreCase(compressionAlgorithm.trim())) {
            throw LocalApiException.of(LocalApiException.CODE_INVALID_REQUEST,
                    "Unsupported FeedDocument compressionAlgorithm=" + compressionAlgorithm);
        }
        try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(bytes))) {
            return gzip.readAllBytes();
        } catch (IOException e) {
            throw LocalApiException.of(LocalApiException.CODE_INVALID_REQUEST,
                    "Feed processing report is not valid GZIP data");
        }
    }

    private String requireString(JsonObject object, String field, String owner) {
        String value = optionalString(object, field);
        if (value == null || value.isBlank()) {
            throw LocalApiException.of(LocalApiException.CODE_INVALID_REQUEST,
                    owner + "." + field + " is required");
        }
        return value;
    }

    private String optionalString(JsonObject object, String field) {
        if (object == null || !object.has(field) || object.get(field).isJsonNull()) {
            return null;
        }
        try {
            return object.get(field).getAsString();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private int intOrDefault(JsonObject object, String field, int fallback) {
        if (object == null || !object.has(field) || object.get(field).isJsonNull()) {
            return fallback;
        }
        try {
            return object.get(field).getAsInt();
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }
    /**
     * 创建 Feed 文档（POST /feeds/2021-06-30/documents），返回 feedDocumentId 与 S3 预签名上传地址。
     */
    private JsonObject createFeedDocument(SpApiGateway.ResolvedShop shop, String contentType) {
        JsonObject req = new JsonObject();
        req.addProperty("contentType", contentType);
        String body = req.toString();
        return gateway.callJsonWithStatuses(
                "POST", shop, OP_CREATE_FEED_DOCUMENT, DOCUMENTS_PATH, null, body, 200, 201);
    }

    /**
     * 将 Feed 内容 PUT 上传到 S3 预签名地址（无需额外 AWS 签名，预签名 URL 已携带鉴权参数）。
     */
    private void uploadDocument(String uploadUrl, String contentType, String content) {
        HttpRequest request;
        try {
            // 预签名 URL 自带鉴权参数：绝不注入 LWA token / AWS 签名（P0-50）
            request = requestFactory.presigned("PUT", uploadUrl, contentType, content,
                    Duration.ofSeconds(30));
        } catch (RuntimeException e) {
            // 与 SpApiGateway.downloadBytes 同一漏洞面（P0-53），只是入口在写侧：
            // URI.create 失败时 JDK 会把完整预签名 URL 回显进异常文本，而**上传**预签名 URL
            // 携带的是写权限。只回显对象路径 + 已脱敏原因，且不链 cause
            // （controller 的 log.error(..., e) 会打印整条链，链上原异常仍带完整 URL）。
            String path = ErrorSummary.objectPath(uploadUrl);
            String reason = ErrorSummary.redact(e.getMessage());
            log.warn("presigned upload rejected path={} reason={}", path, reason);
            throw LocalApiException.of(LocalApiException.CODE_PRESIGNED_URL_INVALID,
                    "upload request rejected path=" + path + " reason=" + reason);
        }
        HttpResponse<String> response;
        try {
            response = httpTransport.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw SpApiCallException.transportFailure(
                    "s3.uploadFeedDocument", ErrorSummary.objectPath(uploadUrl), "interrupted");
        } catch (Exception e) {
            // 传输层异常文本同样过脱敏：HttpClient 的传输异常一般不嵌 URL，但不做假设（P0-53）
            throw SpApiCallException.transportFailure(
                    "s3.uploadFeedDocument", ErrorSummary.objectPath(uploadUrl),
                    ErrorSummary.redact(e.getMessage()));
        }
        if (response.statusCode() != 200) {
            throw SpApiCallException.statusFailure(
                    "s3.uploadFeedDocument", ErrorSummary.objectPath(uploadUrl), response.statusCode());
        }
        log.debug("uploadDocument success");
    }

    /**
     * 创建 Feed（POST /feeds/2021-06-30/feeds），引用 inputFeedDocumentId，返回 feedId。
     */
    private JsonObject createFeed(SpApiGateway.ResolvedShop shop, String feedType,
                                 String marketplaceId, String feedDocumentId) {
        // 分档维度：官方说明 JSON_LISTINGS_FEED 的限流与 createFeed operation 不同，
        // 数值在未纳入模型快照的 Guide 里 → 先用 feedType 隔离窗口、不填猜测值，
        // 取得官方数值或观测头后再收敛（见 SpiRateLimiter.OFFICIAL_PLANS 注释）。
        JsonObject req = new JsonObject();
        req.addProperty("feedType", feedType);
        JsonArray mks = new JsonArray();
        mks.add(marketplaceId);
        req.add("marketplaceIds", mks);
        req.addProperty("inputFeedDocumentId", feedDocumentId);
        String body = req.toString();

        return gateway.callJsonWithVariantAndStatuses(
                "POST", shop, OP_CREATE_FEED, FEEDS_PATH, null, body, feedType, 200, 202);
    }

    /**
     * 发送 HTTP 请求，遇到 429 限流时按指数退避重试。
     * <p>
     * 429 时把 {@code x-amzn-RateLimit-Limit} 回填到 {@code (shopId, operationId[, variant])}
     * 维度：只收紧本店铺本 operation 的速率，观测值回升时自动恢复官方默认上限。
     *
     * @param request     已构建好的 HTTP 请求
     * @param shopId      店铺 ID（限流按店铺隔离）
     * @param operationId 官方 operationId（如 {@code feeds.createFeedDocument}）
     * @param variant     分档值（如 {@code JSON_LISTINGS_FEED}）；无分档传 {@code null}
     */
    private HttpResponse<String> sendWithRetry(HttpRequest request, Long shopId, String operationId,
                                              String variant) {
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
                    spiRateLimiter.updateLimit(shopId, operationId, variant, rateLimitHeader);
                }
                long backoff = (1L << attempt) * 1000L;
                log.warn("Rate limited (429), retrying after {}ms attempt={}", backoff, attempt);
                sleep(backoff);
                continue;
            }
            // 与 429 同样可重试；此前缺这条分支，Feeds 侧遇到 5xx 直接把错误响应当终态返回
            int status = response.statusCode();
            if (status >= 500 && status < 600) {
                long serverErrorBackoff = (1L << attempt) * 1000L;
                log.warn("Server error {} retrying after {}ms attempt={}", status, serverErrorBackoff, attempt);
                sleep(serverErrorBackoff);
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
     * @param operationId 官方 operationId（如 {@code "feeds.createFeed"}）；
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

}
