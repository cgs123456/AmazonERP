package com.amz.client;

import com.amz.auth.LwaTokenManager;
import com.amz.credential.ShopCredential;
import com.amz.credential.ShopCredentialStore;
import com.amz.connector.ErrorSummary;
import com.amz.connector.HttpTransport;
import com.amz.connector.MarketplaceRegistry;
import com.amz.connector.SpApiEndpointResolver;
import com.amz.connector.SpApiRequestFactory;
import com.amz.ratelimit.SpiRateLimiter;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.TreeMap;

/**
 * SP-API 统一调用网关：统一请求构造（条件签名 + 必填 user-agent）+ LWA Token + 限流 + 429 退避重试 + 指标。
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
    private final HttpTransport httpTransport;
    private final LwaTokenManager lwaTokenManager;
    private final ShopCredentialStore shopCredentialStore;
    private final SpApiRequestFactory requestFactory;

    private final SpiRateLimiter spiRateLimiter;
    private final ObjectProvider<MeterRegistry> meterRegistryProvider;

    public SpApiGateway(HttpTransport httpTransport,
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
        // P0-51：端点解析统一走 requestFactory（官方主机或非生产覆盖，与出站主机校验同源）
        SpApiEndpointResolver.Endpoint ep = requestFactory.resolveEndpoint(region);
        String endpoint = ep.baseUrl();
        String host = ep.host();
        // 分组码 NA/EU/FE 不是 AWS region：SigV4 作用域必须用真实 region（P0-48）
        String awsRegion = MarketplaceRegistry.resolveAwsRegion(region);
        return new ResolvedShop(credential, region, endpoint, host, awsRegion);
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
        String accessToken = lwaTokenManager.getToken(shop.credential);
        spiRateLimiter.acquire(shop.credential.getShopId(), operationId);

        // 统一构造点：host / x-amz-date / 条件 Authorization / 必填 user-agent / x-amz-access-token
        // （P0-35 / P0-38 / P0-48 / P0-50 的规则全部收敛在 SpApiRequestFactory）
        HttpRequest request = requestFactory.spApi(
                method, shop.endpoint, shop.host, shop.awsRegion, path, query, body,
                accessToken, shop.credential.getAccessKey(), shop.credential.getSecretKey());

        HttpResponse<String> response = sendWithRetry(request, operationId, shop);
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
            throw new RuntimeException("download request rejected path=" + path + " reason=" + reason);
        }
        try {
            HttpResponse<byte[]> response = httpTransport.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200) {
                log.warn("presigned download failed status={} path={}", response.statusCode(), ErrorSummary.objectPath(url));
                throw new RuntimeException("download failed status=" + response.statusCode()
                        + " path=" + ErrorSummary.objectPath(url));
            }
            return response.body();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("download error path=" + ErrorSummary.objectPath(url)
                    + ": " + ErrorSummary.redact(e.getMessage()), e);
        }
    }

    /**
     * 发送 HTTP 请求，遇到 429 限流时按指数退避重试
     * （模式与 FeedsClient.sendWithRetry 一致：429 时把 {@code x-amzn-RateLimit-Limit}
     * 回填到 {@code (shopId, operationId)} 维度，只影响本店铺本 operation 的后续配额）。
     */
    private HttpResponse<String> sendWithRetry(HttpRequest request, String operationId, ResolvedShop shop) {
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
                    spiRateLimiter.updateLimit(shop.credential.getShopId(), operationId, rateLimitHeader);
                }
                long backoff = (1L << attempt) * 1000L;
                log.warn("Rate limited (429), retrying after {}ms attempt={} operation={}",
                        backoff, attempt, operationId);
                sleep(backoff);
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
        public final String region;
        public final String endpoint;
        public final String host;
        public final String awsRegion;

        public ResolvedShop(ShopCredential credential, String region, String endpoint, String host,
                            String awsRegion) {
            this.credential = credential;
            this.region = region;
            this.endpoint = endpoint;
            this.host = host;
            this.awsRegion = awsRegion;
        }
    }
}
