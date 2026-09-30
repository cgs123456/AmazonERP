package com.amz.client;

import com.amz.auth.LwaTokenManager;
import com.amz.credential.ShopCredential;
import com.amz.credential.ShopCredentialStore;
import com.amz.connector.ErrorSummary;
import com.amz.connector.HttpTransport;
import com.amz.connector.LocalApiException;
import com.amz.connector.MarketplaceRegistry;
import com.amz.connector.RestrictedDataTokenManager;
import com.amz.connector.RestrictedResource;
import com.amz.connector.SpApiCallException;
import com.amz.connector.SpApiEndpointNotAllowedException;
import com.amz.connector.SpApiEndpointResolver;
import com.amz.connector.SpApiRequestFactory;
import com.amz.connector.SpApiTokenSource;
import com.amz.outbox.SpApiCallOutboxService;
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
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * SP-API 统一调用网关：统一请求构造（条件签名 + 必填 user-agent）+ LWA Token + 限流 + 429 退避重试 + 指标。
 * <p>
 * 所有 SP-API JSON 调用都必须经过本网关，以保证请求先进入持久化 Outbox，再出站访问 Amazon。
 * 网关统一处理条件签名、LWA Token、限流、429 退避、401/403 缓存驱逐和审计状态迁移。
 */
@Component
public class SpApiGateway {

    private static final Logger log = LoggerFactory.getLogger(SpApiGateway.class);

    private static final int MAX_RETRIES = 3;

    /** 官方 Notifications grantless 操作使用的固定 scope。 */
    private static final Set<String> NOTIFICATIONS_GRANTLESS_OPERATIONS = Set.of(
            "notifications.getSubscriptionById",
            "notifications.deleteSubscriptionById",
            "notifications.sendTestNotification",
            "notifications.getDestinations",
            "notifications.createDestination",
            "notifications.getDestination",
            "notifications.deleteDestination");
    private static final String NOTIFICATIONS_GRANTLESS_SCOPE = "sellingpartnerapi::notifications";

    /**
     * 区域与官方端点统一由 {@link MarketplaceRegistry} 解析（P0-36 单一事实源）：
     * 未登记的 marketplaceId 或 region 直接抛 {@link com.amz.connector.UnknownMarketplaceException}，
     * 不再静默回落默认区域。
     */
    private final HttpTransport httpTransport;
    private final LwaTokenManager lwaTokenManager;
    private final ShopCredentialStore shopCredentialStore;
    private final SpApiRequestFactory requestFactory;

    private final SpiRateLimiter spiRateLimiter;
    private final ObjectProvider<MeterRegistry> meterRegistryProvider;
    private final SpApiCallOutboxService outboxService;
    private final ObjectProvider<RestrictedDataTokenManager> restrictedDataTokenManagerProvider;

    public SpApiGateway(HttpTransport httpTransport,
                        LwaTokenManager lwaTokenManager,
                        ShopCredentialStore shopCredentialStore,
                        SpiRateLimiter spiRateLimiter,
                        SpApiRequestFactory requestFactory,
                        ObjectProvider<MeterRegistry> meterRegistryProvider) {
        this(httpTransport, lwaTokenManager, shopCredentialStore, spiRateLimiter,
                requestFactory, meterRegistryProvider, null, null);
    }

    public SpApiGateway(HttpTransport httpTransport,
                        LwaTokenManager lwaTokenManager,
                        ShopCredentialStore shopCredentialStore,
                        SpiRateLimiter spiRateLimiter,
                        SpApiRequestFactory requestFactory,
                        ObjectProvider<MeterRegistry> meterRegistryProvider,
                        SpApiCallOutboxService outboxService) {
        this(httpTransport, lwaTokenManager, shopCredentialStore, spiRateLimiter,
                requestFactory, meterRegistryProvider, outboxService, null);
    }

    @Autowired
    public SpApiGateway(HttpTransport httpTransport,
                        LwaTokenManager lwaTokenManager,
                        ShopCredentialStore shopCredentialStore,
                        SpiRateLimiter spiRateLimiter,
                        SpApiRequestFactory requestFactory,
                        ObjectProvider<MeterRegistry> meterRegistryProvider,
                        SpApiCallOutboxService outboxService,
                        ObjectProvider<RestrictedDataTokenManager> restrictedDataTokenManagerProvider) {
        this.httpTransport = httpTransport;
        this.lwaTokenManager = lwaTokenManager;
        this.shopCredentialStore = shopCredentialStore;
        this.spiRateLimiter = spiRateLimiter;
        this.requestFactory = requestFactory;
        this.meterRegistryProvider = meterRegistryProvider;
        this.outboxService = outboxService;
        this.restrictedDataTokenManagerProvider = restrictedDataTokenManagerProvider;
    }

    /**
     * 解析店铺凭证与 SP-API 端点/区域信息（P0-36 fail-closed）。
     * 优先用显式 marketplaceId 映射区域；缺失时回退到凭证登记的 marketplaceId，
     * 再回退到凭证区域；三者都缺失或非法时**抛异常**（与 FeedsClient 的回退链一致）。
     */
    public ResolvedShop resolveShop(Long shopId, String marketplaceId) {
        ShopCredential credential = shopCredentialStore.get(shopId);
        if (credential == null) {
            throw LocalApiException.of(LocalApiException.CODE_CREDENTIAL_MISSING,
                    "No credential found for shopId=" + shopId);
        }
        // fail-closed（P0-36）：显式 marketplaceId → 凭证 marketplaceId → 凭证 region → 抛异常。
        // 旧实现在三者全空时静默回落 NA 端点，会把请求发到未经确认的区域。
        String resolvedMarketplace = firstNonBlank(marketplaceId, credential.getMarketplaceId());
        String region = MarketplaceRegistry.resolveRegion(
                marketplaceId, credential.getMarketplaceId(), credential.getRegion(), "shopId=" + shopId);
        // P0-51：端点解析统一走 requestFactory（官方主机或非生产覆盖，与出站主机校验同源）
        SpApiEndpointResolver.Endpoint ep = requestFactory.resolveEndpoint(region);
        String endpoint = ep.baseUrl();
        String host = ep.host();
        // 分组码 NA/EU/FE 不是 AWS region：SigV4 作用域必须用真实 region（P0-48）
        String awsRegion = MarketplaceRegistry.resolveAwsRegion(region);
        return new ResolvedShop(credential, resolvedMarketplace, region, endpoint, host, awsRegion);
    }

    /**
     * 发起一次 SP-API JSON 调用（带限流、429 退避重试与指标）。
     *
     * @param method      HTTP 方法（GET / POST）
     * @param shop        已解析的店铺信息（含端点与凭证）
     * @param operationId 官方 operationId（限流维度，如 {@code reports.createReport}）；
     *                    未知 operationId 落到保守兜底（1 req/s、burst 30）并打一次 WARN
     * @param path        请求路径，如 /reports/2021-06-30/reports
     * @param query       规范查询串（参数名字典序、URI 编码、&amp; 拼接；无查询传 null）
     * @param body        JSON 请求体（GET 传 null）
     * @return 响应 JSON（HTTP 200 才返回，否则抛 RuntimeException）
     */
    public JsonObject callJson(String method, ResolvedShop shop, String operationId,
                               String path, String query, String body) {
        return callJsonWithStatus(method, shop, operationId, path, query, body, 200);
    }

    /**
     * 发起一次 SP-API JSON 调用，并显式指定唯一成功状态码。
     * <p>
     * Messaging API 的发送类操作官方返回 {@code 201 Created}；把成功码做成显式参数，
     * 避免把任意 {@code 2xx} 都误当成业务成功（例如异步 API 的 {@code 202} 需要上层轮询）。
     * 空成功响应体返回空 JSON 对象，绝不把空体解析成成功数据。
     */
    public JsonObject callJsonWithStatus(String method, ResolvedShop shop, String operationId,
                                         String path, String query, String body,
                                         int expectedStatus) {
        return callJsonWithStatusUsingLwa(method, shop, operationId, path, query, body,
                expectedStatus);
    }

    /**
     * Explicit LWA entry point for operations that must never use an RDT
     * (most importantly the Tokens API itself).
     */
    public JsonObject callJsonWithStatusUsingLwa(String method, ResolvedShop shop,
                                                  String operationId, String path,
                                                  String query, String body,
                                                  int expectedStatus) {
        return callJsonWithVariantAndStatusesUsingLwa(method, shop, operationId, path, query, body,
                null, expectedStatus);
    }

    /**
     * Explicit grantless LWA entry point for official grantless operations.
     * <p>
     * This path uses {@code client_credentials} and never falls back to a seller
     * refresh token or an RDT. It is intentionally separate from the normal LWA
     * entry point so an authentication failure cannot silently change token semantics.
     */
    public JsonObject callJsonWithStatusUsingGrantlessLwa(String method, ResolvedShop shop,
                                                           String operationId, String path,
                                                           String query, String body,
                                                           int expectedStatus) {
        List<Integer> statuses = requireExpectedStatuses(expectedStatus);
        grantlessScopeFor(operationId);
        return executeJson(method, shop, operationId, path, query, body,
                SpApiTokenSource.LWA_GRANTLESS, List.of(), null, statuses, null);
    }
    /**
     * 发起一次 SP-API JSON 调用，允许显式声明多个成功状态码。
     * <p>
     * Feeds 的 {@code createFeed} 官方同时可能返回 200/202，不能用“任意 2xx 都成功”
     * 的宽松判断替代。Outbox 记录第一个状态码作为主成功码，实际成功码完整保存在响应字段中。
     */
    public JsonObject callJsonWithStatuses(String method, ResolvedShop shop, String operationId,
                                           String path, String query, String body,
                                           int... expectedStatuses) {
        return callJsonWithVariantAndStatuses(method, shop, operationId, path, query, body,
                null, expectedStatuses);
    }

    /**
     * 带限流分档值的 SP-API JSON 调用。分档只影响本地限流窗口，不影响 Outbox operationId。
     */
    public JsonObject callJsonWithVariantAndStatuses(String method, ResolvedShop shop,
                                                     String operationId, String path,
                                                     String query, String body,
                                                     String rateLimitVariant,
                                                     int... expectedStatuses) {
        return callJsonWithVariantAndStatusesUsingLwa(method, shop, operationId, path, query, body,
                rateLimitVariant, expectedStatuses);
    }

    private JsonObject callJsonWithVariantAndStatusesUsingLwa(String method, ResolvedShop shop,
                                                              String operationId, String path,
                                                              String query, String body,
                                                              String rateLimitVariant,
                                                              int... expectedStatuses) {
        List<Integer> statuses = requireExpectedStatuses(expectedStatuses);
        return executeJson(method, shop, operationId, path, query, body,
                SpApiTokenSource.LWA, List.of(), rateLimitVariant, statuses, null);
    }

    /**
     * Calls an SP-API operation with an RDT scoped to the exact supplied resources.
     * The token is requested only after the Outbox row exists, and a token failure
     * aborts before any business request is sent.
     */
    public JsonObject callJsonWithRestrictedData(String method, ResolvedShop shop,
                                                 String operationId, String path,
                                                 String query, String body,
                                                 List<RestrictedResource> restrictedResources,
                                                 String rateLimitVariant,
                                                 int... expectedStatuses) {
        List<Integer> statuses = requireExpectedStatuses(expectedStatuses);
        if (restrictedResources == null || restrictedResources.isEmpty()) {
            throw LocalApiException.of(LocalApiException.CODE_INVALID_REQUEST,
                    "restricted resources must not be empty for an RDT call");
        }
        RestrictedResource.canonicalKey(restrictedResources);
        return executeJson(method, shop, operationId, path, query, body,
                SpApiTokenSource.RDT, List.copyOf(restrictedResources),
                rateLimitVariant, statuses, null);
    }

    /**
     * 重放 Outbox 中已领取的记录。
     * <p>
     * 原记录的 {@code REPLAYING -> REPLAYED} 迁移与新记录插入在 Outbox 服务事务内完成；
     * 本次网络调用只更新新记录。调用失败不会把原记录重新丢回自动队列，写操作必须由人工重放。
     */
    public JsonObject replay(SpApiCallOutboxService.ReplayCall replay) {
        if (outboxService == null) {
            throw new IllegalStateException("SP-API Outbox service is not configured; replay is disabled");
        }
        if (replay == null) {
            throw new IllegalArgumentException("replay call is required");
        }
        ResolvedShop shop = resolveShop(replay.shopId(), replay.marketplaceId());
        Long replayOutboxId = outboxService.createReplay(replay);
        return executeJson(replay.httpMethod(), shop, replay.operationId(), replay.path(),
                replay.query(), replay.body(), replay.tokenSource(), replay.restrictedResources(),
                replay.rateLimitVariant(), replay.expectedStatuses(), replayOutboxId);
    }

    private JsonObject executeJson(String method, ResolvedShop shop, String operationId,
                                   String path, String query, String body,
                                   SpApiTokenSource tokenSource,
                                   List<RestrictedResource> restrictedResources,
                                   String rateLimitVariant, List<Integer> expectedStatuses,
                                   Long existingOutboxId) {
        if (expectedStatuses == null || expectedStatuses.isEmpty()) {
            throw LocalApiException.of(LocalApiException.CODE_INVALID_REQUEST,
                    "at least one expected SP-API status is required");
        }
        SpApiTokenSource source = SpApiTokenSource.fromPersisted(
                tokenSource == null ? null : tokenSource.name());
        List<RestrictedResource> resources = validateTokenScope(source, restrictedResources);

        Long outboxId = existingOutboxId == null
                ? beginOutbox(method, shop, operationId, path, query, body,
                expectedStatuses, rateLimitVariant, source, resources)
                : existingOutboxId;
        HttpResponse<String> response = null;
        JsonObject result;
        try {
            acquireRateLimit(shop, operationId, rateLimitVariant);
            String accessToken = resolveAccessToken(shop, source, resources, operationId);
            HttpRequest request = requestFactory.spApi(
                    method, shop.endpoint, shop.host, shop.awsRegion, path, query, body,
                    accessToken, shop.credential.getAccessKey(), shop.credential.getSecretKey());

            response = sendWithRetry(request, operationId, shop, rateLimitVariant);
            int status = response == null ? -1 : response.statusCode();
            if (status == 401 || status == 403) {
                invalidateToken(shop, source, resources, operationId);
                log.warn("SP-API call got {} — token cache invalidated tokenSource={} shopId={} path={}",
                        status, source, shop.credential.getShopId(), path);
            }
            if (response == null) {
                throw SpApiCallException.transportFailure(operationId, path, "no HTTP response");
            }
            if (!isExpectedStatus(status, expectedStatuses)) {
                throw SpApiCallException.fromResponse(operationId, path, response);
            }
            result = parseJsonResponse(operationId, path, response);
        } catch (SpApiCallException e) {
            recordFailure(outboxId, e, response == null ? null : response.body());
            throw e;
        } catch (RuntimeException e) {
            SpApiCallException typed = localFailure(operationId, path, e);
            recordFailure(outboxId, typed, null);
            if (e instanceof LocalApiException || e instanceof SpApiEndpointNotAllowedException) {
                throw e;
            }
            throw typed;
        }

        // 远程调用已成功。若此刻审计落库失败，不能把调用伪装成成功，也绝不能在同一路径自动重试，
        // 因为 Amazon 可能已经产生了业务副作用。
        if (outboxId != null) {
            try {
                outboxService.succeed(outboxId, response);
            } catch (RuntimeException e) {
                log.error("SP-API remote call succeeded but Outbox success update failed id={} operation={} path={}",
                        outboxId, operationId, path, e);
                throw new IllegalStateException(
                        "SP-API remote call succeeded but its Outbox record could not be finalized", e);
            }
        }
        return result;
    }

    /**
     * 下载任意 URL 的原始字节（用于 S3 预签名文档地址，无需再签名）。
     * <p>
     * <b>P0-53</b>：{@code url} 是<b>预签名地址</b>——{@code X-Amz-Signature} / {@code X-Amz-Credential}
     * 等查询参数本身就是凭证（有效期内可被任何人直接下载结算原表）。而本方法抛出的异常文本
     * 会被 controller 返回给调用方并写入错误日志，因此这里<b>只回显状态码 + 对象路径</b>，
     * 不拼接完整 URL，也不回显 S3 错误体（{@code SignatureDoesNotMatch} 等错误体会内嵌规范请求/凭证材料）。
     * 上层如需更强的兜底，边界层还会再走 {@link com.amz.connector.ErrorSummary#redact(String)}。
     */
    public byte[] downloadBytes(String url) {
        HttpRequest request;
        try {
            // 预签名 URL 自带鉴权参数：绝不注入 LWA token / AWS 签名（P0-50）
            request = requestFactory.presigned("GET", url, null, null, Duration.ofSeconds(60));
        } catch (RuntimeException e) {
            // URI 构造失败时 JDK 会把完整 URL 回显在异常文本里（与 P0-53 同类泄露，只是入口不同）。
            // 刻意**不链 cause**：controller 用 log.error(..., e) 打印整条异常链，
            // 链上原异常仍带完整预签名 URL，等于把漏洞从响应搬到日志。
            String path = ErrorSummary.objectPath(url);
            String reason = ErrorSummary.redact(e.getMessage());
            log.warn("presigned download rejected path={} reason={}", path, reason);
            throw LocalApiException.of(LocalApiException.CODE_PRESIGNED_URL_INVALID,
                    "download request rejected path=" + path + " reason=" + reason);
        }
        HttpResponse<byte[]> response;
        try {
            response = httpTransport.send(request, HttpResponse.BodyHandlers.ofByteArray());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw SpApiCallException.transportFailure(
                    "s3.downloadDocument", ErrorSummary.objectPath(url), "interrupted");
        } catch (Exception e) {
            String reason = ErrorSummary.redact(e.getMessage());
            log.warn("presigned download transport error path={} reason={}",
                    ErrorSummary.objectPath(url), reason);
            // 不链 cause：传输驱动可能把完整预签名 URL 放进异常消息。
            throw SpApiCallException.transportFailure(
                    "s3.downloadDocument", ErrorSummary.objectPath(url), reason);
        }
        if (response.statusCode() != 200) {
            log.warn("presigned download failed status={} path={}", response.statusCode(), ErrorSummary.objectPath(url));
            throw SpApiCallException.statusFailure(
                    "s3.downloadDocument", ErrorSummary.objectPath(url), response.statusCode());
        }
        return response.body();
    }

    /**
     * 将原始字节 PUT 到 S3 预签名上传地址。
     * <p>
     * Uploads API 返回的 {@code headers} 必须原样带入（常见为 {@code Content-MD5} 与
     * {@code x-amz-server-side-encryption}），否则预签名校验会失败；但预签名 URL
     * 已自带鉴权，绝不能额外注入 LWA access token 或 AWS SigV4。
     * <p>
     * 异常文本只保留状态码与对象路径，不拼接预签名查询串，也不链传输层 cause
     * （P0-53：上传 URL 携带写权限，泄露后果高于下载 URL）。
     *
     * @param url         预签名 PUT URL
     * @param headers     S3 要求附加的请求头，可为空
     * @param contentType 内容类型，可为空
     * @param body        原始字节体
     */
    public void uploadBytes(String url, Map<String, String> headers, String contentType, byte[] body) {
        if (body == null) {
            throw LocalApiException.of(LocalApiException.CODE_INVALID_REQUEST,
                    "upload body must not be null");
        }
        HttpRequest request;
        try {
            request = requestFactory.presigned("PUT", url, contentType, body,
                    Duration.ofSeconds(60), headers);
        } catch (RuntimeException e) {
            String path = ErrorSummary.objectPath(url);
            String reason = ErrorSummary.redact(e.getMessage());
            log.warn("presigned upload rejected path={} reason={}", path, reason);
            throw LocalApiException.of(LocalApiException.CODE_PRESIGNED_URL_INVALID,
                    "upload request rejected path=" + path + " reason=" + reason);
        }
        HttpResponse<byte[]> response;
        try {
            response = httpTransport.send(request, HttpResponse.BodyHandlers.ofByteArray());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw SpApiCallException.transportFailure(
                    "s3.uploadDocument", ErrorSummary.objectPath(url), "interrupted");
        } catch (Exception e) {
            String reason = ErrorSummary.redact(e.getMessage());
            log.warn("presigned upload transport error path={} reason={}",
                    ErrorSummary.objectPath(url), reason);
            // 不链 cause：传输驱动可能把完整预签名 URL 放进异常消息。
            throw SpApiCallException.transportFailure(
                    "s3.uploadDocument", ErrorSummary.objectPath(url), reason);
        }
        if (response.statusCode() != 200) {
            log.warn("presigned upload failed status={} path={}",
                    response.statusCode(), ErrorSummary.objectPath(url));
            throw SpApiCallException.statusFailure(
                    "s3.uploadDocument", ErrorSummary.objectPath(url), response.statusCode());
        }
    }

    private static List<Integer> requireExpectedStatuses(int... expectedStatuses) {
        if (expectedStatuses == null || expectedStatuses.length == 0) {
            throw LocalApiException.of(LocalApiException.CODE_INVALID_REQUEST,
                    "at least one expected SP-API status is required");
        }
        List<Integer> statuses = Arrays.stream(expectedStatuses).boxed().toList();
        for (int status : statuses) {
            if (status < 100 || status > 599) {
                throw LocalApiException.of(LocalApiException.CODE_INVALID_REQUEST,
                        "expected SP-API status must be between 100 and 599");
            }
        }
        return statuses;
    }

    private static List<RestrictedResource> validateTokenScope(
            SpApiTokenSource source, List<RestrictedResource> restrictedResources) {
        if (source == null) {
            throw LocalApiException.of(LocalApiException.CODE_INVALID_REQUEST,
                    "SP-API token source is required");
        }
        switch (source) {
            case LWA, LWA_GRANTLESS -> {
                rejectRestrictedResources(source, restrictedResources);
                return List.of();
            }
            case RDT -> {
                if (restrictedResources == null || restrictedResources.isEmpty()) {
                    throw LocalApiException.of(LocalApiException.CODE_INVALID_REQUEST,
                            "restricted resources must not be empty for an RDT call");
                }
                RestrictedResource.canonicalKey(restrictedResources);
                return List.copyOf(restrictedResources);
            }
            default -> throw LocalApiException.of(LocalApiException.CODE_INVALID_REQUEST,
                    "unsupported SP-API token source: " + source);
        }
    }

    private static void rejectRestrictedResources(SpApiTokenSource source,
                                                  List<RestrictedResource> restrictedResources) {
        if (restrictedResources != null && !restrictedResources.isEmpty()) {
            throw LocalApiException.of(LocalApiException.CODE_INVALID_REQUEST,
                    source + " calls must not declare restricted resources");
        }
    }

    private String resolveAccessToken(ResolvedShop shop, SpApiTokenSource source,
                                      List<RestrictedResource> restrictedResources,
                                      String operationId) {
        return switch (source) {
            case LWA -> lwaTokenManager.getToken(shop.credential);
            case LWA_GRANTLESS -> lwaTokenManager.getGrantlessToken(
                    shop.credential.getClientId(),
                    shop.credential.getClientSecret(),
                    grantlessScopeFor(operationId));
            case RDT -> restrictedDataTokenManager().getToken(
                    shop.credential.getShopId(), shop.marketplaceId, restrictedResources);
        };
    }

    private void invalidateToken(ResolvedShop shop, SpApiTokenSource source,
                                 List<RestrictedResource> restrictedResources,
                                 String operationId) {
        try {
            switch (source) {
                case LWA -> lwaTokenManager.invalidate(shop.credential);
                case LWA_GRANTLESS -> lwaTokenManager.invalidateGrantless(
                        shop.credential.getClientId(),
                        shop.credential.getClientSecret(),
                        grantlessScopeFor(operationId));
                case RDT -> restrictedDataTokenManager().invalidate(
                        shop.credential.getShopId(), shop.marketplaceId, restrictedResources);
            }
        } catch (RuntimeException e) {
            log.warn("SP-API token invalidation failed tokenSource={} shopId={} reason={}",
                    source, shop.credential.getShopId(), ErrorSummary.redact(e.getMessage()));
        }
    }

    private static String grantlessScopeFor(String operationId) {
        if (NOTIFICATIONS_GRANTLESS_OPERATIONS.contains(operationId)) {
            return NOTIFICATIONS_GRANTLESS_SCOPE;
        }
        throw LocalApiException.of(LocalApiException.CODE_INVALID_REQUEST,
                "operation is not registered for grantless LWA: " + operationId);
    }

    private RestrictedDataTokenManager restrictedDataTokenManager() {
        if (restrictedDataTokenManagerProvider == null) {
            throw LocalApiException.of(
                    RestrictedDataTokenManager.CODE_RESTRICTED_DATA_DISABLED,
                    "Restricted data token manager is not configured");
        }
        RestrictedDataTokenManager manager = restrictedDataTokenManagerProvider.getIfAvailable();
        if (manager == null) {
            throw LocalApiException.of(
                    RestrictedDataTokenManager.CODE_RESTRICTED_DATA_DISABLED,
                    "Restricted data token manager is not configured");
        }
        return manager;
    }

    private Long beginOutbox(String method, ResolvedShop shop, String operationId,
                             String path, String query, String body,
                             List<Integer> expectedStatuses, String rateLimitVariant,
                             SpApiTokenSource tokenSource,
                             List<RestrictedResource> restrictedResources) {
        if (outboxService == null) {
            return null;
        }
        return outboxService.begin(SpApiCallOutboxService.CallRequest.of(
                shop.credential.getShopId(), shop.marketplaceId, operationId,
                method, path, query, body, expectedStatuses, rateLimitVariant,
                tokenSource, restrictedResources));
    }
    private void acquireRateLimit(ResolvedShop shop, String operationId, String variant) {
        if (variant == null || variant.isBlank()) {
            spiRateLimiter.acquire(shop.credential.getShopId(), operationId);
        } else {
            spiRateLimiter.acquire(shop.credential.getShopId(), operationId, variant);
        }
    }

    private void recordFailure(Long outboxId, SpApiCallException error, String rawResponseBody) {
        if (outboxId == null || outboxService == null) {
            return;
        }
        try {
            outboxService.fail(outboxId, error, rawResponseBody);
        } catch (RuntimeException outboxError) {
            log.error("SP-API Outbox failure update failed id={} operation={} status={}",
                    outboxId, error.getOperationId(), error.getPlatformStatus(), outboxError);
        }
    }

    private static SpApiCallException localFailure(String operationId, String path, RuntimeException error) {
        if (error instanceof LocalApiException local) {
            return SpApiCallException.localFailure(operationId, path, local.getCode(), local.getMessage());
        }
        return SpApiCallException.localFailure(operationId, path, "LOCAL_ERROR", error.getMessage());
    }

    private static JsonObject parseJsonResponse(String operationId, String path,
                                                HttpResponse<String> response) {
        if (response.body() == null || response.body().isBlank()) {
            return new JsonObject();
        }
        try {
            return JsonParser.parseString(response.body()).getAsJsonObject();
        } catch (RuntimeException e) {
            throw SpApiCallException.localFailure(operationId, path,
                    "INVALID_JSON_RESPONSE", e.getMessage());
        }
    }

    private static boolean isExpectedStatus(int actual, List<Integer> expectedStatuses) {
        for (int expected : expectedStatuses) {
            if (actual == expected) {
                return true;
            }
        }
        return false;
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    /**
     * 发送 HTTP 请求，遇到 429 限流时按指数退避重试。
     * 429 时把 {@code x-amzn-RateLimit-Limit} 回填到与主动限流完全相同的
     * {@code (shopId, operationId[, variant])} 维度。
     */
    private HttpResponse<String> sendWithRetry(HttpRequest request, String operationId,
                                               ResolvedShop shop, String rateLimitVariant) {
        HttpResponse<String> response = null;
        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            try {
                response = httpTransport.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
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
                recordThrottle(operationId);
                String rateLimitHeader = response.headers()
                        .firstValue("x-amzn-RateLimit-Limit").orElse(null);
                if (rateLimitHeader != null && !rateLimitHeader.isBlank()) {
                    if (rateLimitVariant == null || rateLimitVariant.isBlank()) {
                        spiRateLimiter.updateLimit(shop.credential.getShopId(), operationId, rateLimitHeader);
                    } else {
                        spiRateLimiter.updateLimit(shop.credential.getShopId(), operationId,
                                rateLimitVariant, rateLimitHeader);
                    }
                }
                long backoff = (1L << attempt) * 1000L;
                log.warn("Rate limited (429), retrying after {}ms attempt={} operation={}",
                        backoff, attempt, operationId);
                sleep(backoff);
                continue;
            }
            // 5xx 同 429 一样可重试；缺这条分支时网关路径会把 503 当终态返回
            int status = response.statusCode();
            if (status >= 500 && status < 600) {
                long serverErrorBackoff = (1L << attempt) * 1000L;
                log.warn("Server error {} retrying after {}ms attempt={} operation={}",
                        status, serverErrorBackoff, attempt, operationId);
                sleep(serverErrorBackoff);
                continue;
            }
            return response;
        }
        return response;
    }

    /**
     * 记录 SP-API 429 限流触发次数到 Micrometer（软依赖，不影响主链路）。
     * <p>
     * 指标名与标签名（{@code spapi.throttle.count} / {@code endpoint}）保持不变，
     * 但标签值从粗粒度端点（{@code "reports"}）升级为官方 operationId
     * （{@code "reports.createReport"}）：按指标名聚合的仪表盘不受影响，
     * 按端点值过滤的查询需要同步更新（口径变化已在 spec 第 50 轮登记）。
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
     * 店铺解析结果：凭证 + 分组区域码 + 端点 + 主机名 + AWS region。
     * <p>
     * {@code region} 是分组码（NA/EU/FE，决定端点主机），{@code awsRegion} 是
     * 参与 SigV4 作用域的真实 AWS region（P0-48：两者不可混用）。
     */
    public static final class ResolvedShop {
        public final ShopCredential credential;
        public final String marketplaceId;
        public final String region;
        public final String endpoint;
        public final String host;
        public final String awsRegion;

        public ResolvedShop(ShopCredential credential, String marketplaceId, String region,
                            String endpoint, String host, String awsRegion) {
            this.credential = credential;
            this.marketplaceId = marketplaceId;
            this.region = region;
            this.endpoint = endpoint;
            this.host = host;
            this.awsRegion = awsRegion;
        }
    }
}
