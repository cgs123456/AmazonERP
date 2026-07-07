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
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Login with Amazon (LWA) Access Token 管理器。
 * <p>
 * 使用 ConcurrentHashMap 缓存每家店铺的 access_token，并在过期前 5 分钟自动刷新。
 * 提供 {@link #invalidate(String)} 方法在凭证失效（如 401）时主动失效缓存。
 */
@Component
public class LwaTokenManager {

    private static final Logger log = LoggerFactory.getLogger(LwaTokenManager.class);

    /**
     * Token 提前刷新阈值：过期前 5 分钟视为即将过期，触发刷新。
     */
    private static final Duration REFRESH_AHEAD = Duration.ofMinutes(5);

    /**
     * key = LWA clientId，value = 该 clientId 对应的 token 缓存条目。
     */
    private final Map<String, TokenEntry> cache = new ConcurrentHashMap<>();

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    @Autowired
    private SpApiConfig spApiConfig;

    /**
     * 获取指定店铺凭证对应的 LWA access_token。
     * 命中缓存且未临近过期则直接返回，否则加锁刷新。
     */
    public String getToken(ShopCredential credential) {
        if (credential == null) {
            throw new IllegalArgumentException("ShopCredential must not be null");
        }
        String cacheKey = credential.getClientId();
        Instant now = Instant.now();
        TokenEntry entry = cache.get(cacheKey);
        if (entry != null && entry.expiresAt.isAfter(now.plus(REFRESH_AHEAD))) {
            return entry.accessToken;
        }
        synchronized (this) {
            entry = cache.get(cacheKey);
            if (entry != null && entry.expiresAt.isAfter(now.plus(REFRESH_AHEAD))) {
                return entry.accessToken;
            }
            entry = refreshToken(credential);
            cache.put(cacheKey, entry);
            return entry.accessToken;
        }
    }

    /**
     * 主动失效指定 clientId 的 token 缓存。
     * 在 SP-API 返回 401/403 时调用，强制下次重新获取。
     */
    public void invalidate(String clientId) {
        if (clientId != null) {
            cache.remove(clientId);
            log.info("LWA token cache invalidated for clientId={}", clientId);
        }
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
