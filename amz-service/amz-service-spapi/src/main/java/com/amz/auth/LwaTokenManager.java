package com.amz.auth;

import com.amz.config.SpApiConfig;
import com.amz.credential.ShopCredential;
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
import java.util.Objects;
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
 */
@Component
public class LwaTokenManager {

    private static final Logger log = LoggerFactory.getLogger(LwaTokenManager.class);

    /**
     * Token 提前刷新阈值：过期前 5 分钟视为即将过期，触发刷新。
     */
    private static final Duration REFRESH_AHEAD = Duration.ofMinutes(5);

    /**
     * key = clientId:sha256(refreshToken)，value = 对应的 token 缓存条目。
     */
    private final Map<String, TokenEntry> cache = new ConcurrentHashMap<>();

    /**
     * 按缓存键粒度的刷新锁：避免全局锁把所有店铺的 token 刷新串行化
     * （一个慢 LWA 请求不再阻塞其他店铺的调用）。
     */
    private final ConcurrentHashMap<String, Object> keyLocks = new ConcurrentHashMap<>();

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    @Autowired
    private SpApiConfig spApiConfig;

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
     */
    private TokenEntry refreshToken(ShopCredential credential) {
        String form = "grant_type=refresh_token"
                + "&refresh_token=" + encode(credential.getRefreshToken())
                + "&client_id=" + encode(credential.getClientId())
                + "&client_secret=" + encode(credential.getClientSecret());

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(spApiConfig.getLwaEndpoint()))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .timeout(Duration.ofSeconds(15))
                .POST(HttpRequest.BodyPublishers.ofString(form, StandardCharsets.UTF_8))
                .build();

        try {
            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) {
                throw new RuntimeException("LWA token refresh failed status=" + response.statusCode()
                        + " body=" + response.body());
            }
            JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
            String accessToken = json.get("access_token").getAsString();
            int expiresIn = json.has("expires_in") && !json.get("expires_in").isJsonNull()
                    ? json.get("expires_in").getAsInt() : 3600;
            Instant expiresAt = Instant.now().plusSeconds(expiresIn);
            log.info("LWA token refreshed for clientId={} expires_in={}s", credential.getClientId(), expiresIn);
            return new TokenEntry(accessToken, expiresAt);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("LWA token refresh error for clientId=" + credential.getClientId(), e);
        }
    }

    private String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

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
            // JVM 均支持 SHA-256，理论不可达；兜底退化为 hashCode 避免中断鉴权链路
            return Integer.toHexString(Objects.hashCode(input));
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
