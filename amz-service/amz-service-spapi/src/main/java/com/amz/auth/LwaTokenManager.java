package com.amz.auth;

import com.amz.config.SpApiConfig;
import com.amz.connector.HttpTransport;
import com.amz.connector.SpApiEndpointResolver;
import com.amz.credential.ShopCredential;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Login with Amazon (LWA) Access Token 管理器。
 * <p>
 * 使用 ConcurrentHashMap 缓存每家店铺的 access_token，并在过期前 5 分钟自动刷新。
 * <p>
 * <b>缓存键语义（重要）：</b>缓存键为 {@code clientId:sha256(refreshToken)}。
 * 多店铺共用同一 LWA 应用（同 clientId）但持有各自 refresh_token 时，
 * 若仅按 clientId 键控会导致店铺间串用 access_token（跨租户数据泄露 / SP-API 403）。
 * <p>
 * 提供 {@link #invalidate(String)}（按 clientId 批量驱逐）与
 * {@link #invalidate(ShopCredential)}（精确驱逐单店铺）两种失效方式，
 * 在凭证失效（如 401）时主动清除缓存。
 * <p>
 * <b>出站传输（Task 11）：</b>token 交换走 {@link HttpTransport}，因此请求契约
 * （endpoint / 表单 / 状态码 / 失败面）可在零 socket 的进程内桩上断言（证据 E2）。
 * 生产装配由 Spring 注入 {@code HttpTransport} Bean；保留无参构造器供既有单测与
 * 显式装配使用，此时在**首次出站**才惰性创建 JDK {@code HttpClient}
 * （构造期不建连接，缺少网络栈的环境也能构造并使用缓存路径）。
 * <p>
 * <b>失败策略（P0-49）：</b>全部 fail-closed——缺 access_token、非 200、传输异常、
 * 摘要算法不可用，一律抛异常，绝不返回空 token 或退化摘要继续调用。
 */
@Component
public class LwaTokenManager {

    private static final Logger log = LoggerFactory.getLogger(LwaTokenManager.class);

    /**
     * Token 提前刷新阈值：过期前 5 分钟视为即将过期，触发刷新。
     */
    private static final Duration REFRESH_AHEAD = Duration.ofMinutes(5);

    /** LWA token 端点请求超时（官方端点对端到端时延敏感）。 */
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);

    /**
     * key = clientId:sha256(refreshToken)，value = 对应的 token 缓存条目。
     */
    private final Map<String, TokenEntry> cache = new ConcurrentHashMap<>();

    /**
     * 按缓存键粒度的刷新锁：避免全局锁把所有店铺的 token 刷新串行化
     * （一个慢 LWA 请求不再阻塞其他店铺的调用）。
     */
    private final ConcurrentHashMap<String, Object> keyLocks = new ConcurrentHashMap<>();

    /**
     * 显式注入的出站传输（生产由 Spring 注入；亦由契约测试注入进程内桩）。
     */
    private final HttpTransport injectedTransport;

    /**
     * 无参构造路径惰性创建的默认传输；volatile + 双重检查，避免每次出站都重建。
     */
    private volatile HttpTransport lazyTransport;

    /**
     * 端点解析器（P0-51）：非空且存在覆盖时，LWA 交换走覆盖端点。
     * 为 null（无参/两参构造路径）时回退 {@link SpApiConfig#getLwaEndpoint()}。
     */
    private final SpApiEndpointResolver endpointResolver;

    /**
     * 保留字段注入：兼容既有单测（ReflectionTestUtils 注入）与无参构造路径。
     */
    @Autowired
    private SpApiConfig spApiConfig;

    /**
     * 无参构造器：保留给既有单测与显式装配；出站传输在首次使用时惰性创建。
     */
    public LwaTokenManager() {
        this.injectedTransport = null;
        this.endpointResolver = null;
    }

    /**
     * 生产构造路径：显式注入出站传输、配置与端点解析器（P0-51，Spring 装配走这一个）。
     */
    @Autowired
    public LwaTokenManager(HttpTransport transport, SpApiConfig spApiConfig,
                           SpApiEndpointResolver endpointResolver) {
        this.injectedTransport = transport;
        this.spApiConfig = spApiConfig;
        this.endpointResolver = endpointResolver;
    }

    /**
     * 兼容构造路径（无端点覆盖）：保留给既有契约测试与显式装配。
     */
    public LwaTokenManager(HttpTransport transport, SpApiConfig spApiConfig) {
        this(transport, spApiConfig, null);
    }

    /**
     * 计算凭证对应的缓存键。包内可见以便单测复用同一套键推导逻辑。
     * <p>
     * 必须包含 refreshToken 维度：同 clientId 不同店铺（refresh_token 不同）
     * 的 token 缓存必须相互隔离。
     */
    static String cacheKeyOf(String clientId, String refreshToken) {
        return clientId + ":" + sha256Hex(refreshToken == null ? "" : refreshToken);
    }

    /**
     * 获取指定店铺凭证对应的 LWA access_token。
     * 命中缓存且未临近过期则直接返回，否则按键加锁刷新（同键互斥、跨键并行）。
     */
    public String getToken(ShopCredential credential) {
        if (credential == null) {
            throw new IllegalArgumentException("ShopCredential must not be null");
        }
        String key = cacheKeyOf(credential.getClientId(), credential.getRefreshToken());
        TokenEntry entry = cache.get(key);
        if (entry != null && entry.expiresAt.isAfter(Instant.now().plus(REFRESH_AHEAD))) {
            return entry.accessToken;
        }
        Object lock = keyLocks.computeIfAbsent(key, k -> new Object());
        synchronized (lock) {
            // 双重检查：等锁期间可能已被同键线程刷新
            entry = cache.get(key);
            if (entry != null && entry.expiresAt.isAfter(Instant.now().plus(REFRESH_AHEAD))) {
                return entry.accessToken;
            }
            entry = refreshToken(credential);
            cache.put(key, entry);
            return entry.accessToken;
        }
    }

    /**
     * 按 clientId 批量驱逐该应用下所有店铺的 token 缓存。
     * 在 SP-API 返回 401/403 且无法定位具体店铺时使用。
     */
    public void invalidate(String clientId) {
        if (clientId == null) {
            return;
        }
        String prefix = clientId + ":";
        cache.keySet().removeIf(k -> k != null && k.startsWith(prefix));
        log.info("LWA token cache invalidated for clientId={}", clientId);
    }

    /**
     * 精确驱逐单个店铺（clientId + refreshToken 组合）的 token 缓存。
     */
    public void invalidate(ShopCredential credential) {
        if (credential == null) {
            return;
        }
        String key = cacheKeyOf(credential.getClientId(), credential.getRefreshToken());
        cache.remove(key);
        log.info("LWA token cache invalidated for clientId={} shopId={}",
                credential.getClientId(), credential.getShopId());
    }

    /**
     * 调用 LWA 端点刷新 access_token。
     * <p>
     * 官方契约（一手）：{@code POST https://api.amazon.com/auth/o2/token}，
     * {@code Content-Type: application/x-www-form-urlencoded}，表单含
     * {@code grant_type=refresh_token} / {@code refresh_token} / {@code client_id} / {@code client_secret}。
     */
    private TokenEntry refreshToken(ShopCredential credential) {
        String form = "grant_type=refresh_token"
                + "&refresh_token=" + encode(credential.getRefreshToken())
                + "&client_id=" + encode(credential.getClientId())
                + "&client_secret=" + encode(credential.getClientSecret());

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(lwaEndpoint()))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .timeout(REQUEST_TIMEOUT)
                .POST(HttpRequest.BodyPublishers.ofString(form, StandardCharsets.UTF_8))
                .build();

        try {
            HttpResponse<String> response = transport().send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) {
                throw new RuntimeException("LWA token refresh failed status=" + response.statusCode()
                        + " body=" + response.body());
            }
            JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
            String accessToken = requiredString(json, "access_token", credential.getClientId());
            String tokenType = requiredString(json, "token_type", credential.getClientId());
            if (!"bearer".equalsIgnoreCase(tokenType)) {
                throw new RuntimeException("LWA token refresh returned invalid token_type="
                        + tokenType + " clientId=" + credential.getClientId());
            }
            int expiresIn = requiredPositiveInt(json, "expires_in", credential.getClientId());
            Instant expiresAt = Instant.now().plusSeconds(expiresIn);
            log.info("LWA token refreshed for clientId={} expires_in={}s", credential.getClientId(), expiresIn);
            return new TokenEntry(accessToken, expiresAt);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("LWA token refresh error for clientId=" + credential.getClientId(), e);
        }
    }

    /**
     * 读取官方成功响应中的必填字符串字段；缺失、null、非字符串或空白均 fail-closed。
     */
    private static String requiredString(JsonObject json, String field, String clientId) {
        JsonElement element = json.get(field);
        if (element == null || element.isJsonNull() || !element.isJsonPrimitive()
                || !element.getAsJsonPrimitive().isString()) {
            throw new RuntimeException("LWA token refresh response missing or invalid " + field
                    + " clientId=" + clientId);
        }
        String value = element.getAsString();
        if (value.isBlank()) {
            throw new RuntimeException("LWA token refresh response returned blank " + field
                    + " clientId=" + clientId);
        }
        return value;
    }

    /**
     * 读取官方成功响应中的必填正整数 {@code expires_in}；缺失、非法或非正数均 fail-closed。
     */
    private static int requiredPositiveInt(JsonObject json, String field, String clientId) {
        JsonElement element = json.get(field);
        if (element == null || element.isJsonNull() || !element.isJsonPrimitive()
                || !element.getAsJsonPrimitive().isNumber()) {
            throw new RuntimeException("LWA token refresh response missing or invalid " + field
                    + " clientId=" + clientId);
        }
        final int value;
        try {
            value = element.getAsInt();
        } catch (RuntimeException e) {
            throw new RuntimeException("LWA token refresh response invalid " + field
                    + " clientId=" + clientId, e);
        }
        if (value <= 0) {
            throw new RuntimeException("LWA token refresh response returned non-positive " + field
                    + "=" + value + " clientId=" + clientId);
        }
        return value;
    }

    /**
     * 本次 LWA 交换使用的端点：解析器给出的覆盖端点优先，否则回退配置项。
     * （两者都为空时回退官方默认端点，绝不落到相对/空 URL。）
     */
    private String lwaEndpoint() {
        if (endpointResolver != null) {
            String overridden = endpointResolver.lwaEndpoint();
            if (overridden != null && !overridden.isBlank()) {
                return overridden;
            }
        }
        String configured = spApiConfig == null ? null : spApiConfig.getLwaEndpoint();
        return configured == null || configured.isBlank()
                ? SpApiEndpointResolver.DEFAULT_LWA_ENDPOINT
                : configured;
    }

    /**
     * 返回本次出站使用的传输：优先显式注入的实现，否则惰性创建 JDK HttpClient 适配器。
     */
    private HttpTransport transport() {
        HttpTransport explicit = injectedTransport;
        if (explicit != null) {
            return explicit;
        }
        HttpTransport lazy = lazyTransport;
        if (lazy == null) {
            synchronized (this) {
                lazy = lazyTransport;
                if (lazy == null) {
                    lazy = createDefaultTransport();
                    lazyTransport = lazy;
                }
            }
        }
        return lazy;
    }

    private static HttpTransport createDefaultTransport() {
        try {
            HttpClient httpClient = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .build();
            return httpClient::send;
        } catch (Exception e) {
            throw new IllegalStateException(
                    "无法创建默认 JDK HttpClient（缺少可用网络栈/事件循环）：请注入 HttpTransport Bean", e);
        }
    }

    private String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    /**
     * 计算 SHA-256 十六进制摘要（用于缓存键，避免明文 refresh_token 进日志/堆转储）。
     * <p>
     * P0-49：旧实现 catch 后静默退化为 {@code Integer.toHexString(hashCode)}——
     * 那是 32 位非密码学摘要，会让不同 refresh_token 碰撞到同一缓存键（跨店铺串号）。
     * 现在 fail-closed：算法不可用即抛异常，绝不降级。
     */
    private static String sha256Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] bytes = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 不可用，拒绝退化为非密码学摘要（fail-closed）", e);
        }
    }

    /**
     * Token 缓存条目。
     */
    private static class TokenEntry {
        final String accessToken;
        final Instant expiresAt;

        TokenEntry(String accessToken, Instant expiresAt) {
            this.accessToken = accessToken;
            this.expiresAt = expiresAt;
        }
    }
}