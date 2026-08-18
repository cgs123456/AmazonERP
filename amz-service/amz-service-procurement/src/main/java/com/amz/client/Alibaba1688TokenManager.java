package com.amz.client;

import com.amz.http.ResilientHttpClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;

/**
 * 1688 开放平台 access_token 管理器。
 * <p>
 * 职责：
 * <ul>
 *   <li>从 Redis 读取缓存的 access_token（命中且未过期则直接返回，避免频繁刷新）。</li>
 *   <li>缓存未命中 / 过期时，以 {@code refresh_token} 模式向 1688 网关换取新 token，
 *       写回 Redis（TTL = expires_in − 缓冲）。</li>
 *   <li>所有出站调用统一走 {@link ResilientHttpClient}（target=1688，带超时/重试/熔断）。</li>
 * </ul>
 * 凭证缺失（appKey/appSecret/refreshToken 任一为空）时抛出 {@link IllegalStateException}（诚实失败），
 * 由调用方决定告警或降级，而非静默返回过期/伪造 token。
 * <p>
 * ⚠️ <b>未校准。</b> 1688 token 端点与 grant 参数名以官方沙箱为准。此处采用通用
 * refresh_token 结构：以网关根地址 + 系统参数 {@code method=com.alibaba.oauth2.getToken}
 * 发起表单请求。接入沙箱后若端点/字段名有差异，仅需调整 {@link #tokenEndpoint} 与
 * {@link #buildRefreshParams()} 两处。
 */
@Slf4j
public class Alibaba1688TokenManager {

    private static final String ACCESS_TOKEN_KEY = "alibaba:open:access_token";
    private static final String REFRESH_TOKEN_KEY = "alibaba:open:refresh_token";
    private static final long EXPIRY_BUFFER_SECONDS = 300;

    private final ResilientHttpClient http;
    private final StringRedisTemplate redisTemplate;
    private final String appKey;
    private final String appSecret;
    private final String refreshToken;
    private final String tokenEndpoint;

    private final ObjectMapper objectMapper = new ObjectMapper();

    public Alibaba1688TokenManager(ResilientHttpClient http, StringRedisTemplate redisTemplate,
                                   String appKey, String appSecret, String refreshToken, String gateway) {
        this.http = http;
        this.redisTemplate = redisTemplate;
        this.appKey = appKey;
        this.appSecret = appSecret;
        this.refreshToken = refreshToken;
        // 1688 token 接口以系统参数 method 区分，走网关根地址
        this.tokenEndpoint = (gateway == null || gateway.isBlank())
                ? "https://gw.open.1688.com/openapi" : gateway;
    }

    /**
     * 获取当前有效的 access_token：优先 Redis 缓存，否则刷新。
     */
    public String getAccessToken() {
        if (redisTemplate != null) {
            String cached = redisTemplate.opsForValue().get(ACCESS_TOKEN_KEY);
            if (cached != null && !cached.isBlank()) {
                return cached;
            }
        }
        return refresh();
    }

    private String refresh() {
        if (isBlank(appKey) || isBlank(appSecret) || isBlank(refreshToken)) {
            throw new IllegalStateException(
                    "1688 凭证未配置（appKey/appSecret/refreshToken），无法获取 access_token");
        }
        if (http == null) {
            throw new IllegalStateException(
                    "ResilientHttpClient 未注入，1688 无法发起 token 刷新（请通过 Spring 容器获取 TokenManager）");
        }
        TreeMap<String, String> params = buildRefreshParams();
        String body = toFormBody(params);
        String resp = http.post("1688", tokenEndpoint,
                Map.of("Content-Type", "application/x-www-form-urlencoded"), body);
        try {
            JsonNode root = objectMapper.readTree(resp);
            String accessToken = root.path("access_token").asText(null);
            if (accessToken == null) {
                throw new IllegalStateException("1688 token 刷新失败：响应缺少 access_token，resp=" + resp);
            }
            long expiresIn = root.path("expires_in").asLong(0);
            cache(ACCESS_TOKEN_KEY, accessToken, expiresIn);
            String newRefresh = root.path("refresh_token").asText(null);
            if (newRefresh != null && !newRefresh.isBlank()) {
                cache(REFRESH_TOKEN_KEY, newRefresh, expiresIn);
            }
            log.info("1688 access_token 刷新成功，expiresIn={}s", expiresIn);
            return accessToken;
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("1688 token 刷新响应解析失败 resp=" + resp, e);
        }
    }

    private TreeMap<String, String> buildRefreshParams() {
        TreeMap<String, String> params = new TreeMap<>();
        params.put("method", "com.alibaba.oauth2.getToken");
        params.put("grant_type", "refresh_token");
        params.put("client_id", appKey);
        params.put("client_secret", appSecret);
        params.put("refresh_token", refreshToken);
        return params;
    }

    private void cache(String key, String value, long expiresIn) {
        if (redisTemplate == null) {
            return;
        }
        long ttl = Math.max(60, expiresIn - EXPIRY_BUFFER_SECONDS);
        redisTemplate.opsForValue().set(key, value, ttl, TimeUnit.SECONDS);
    }

    private static String toFormBody(Map<String, String> params) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : params.entrySet()) {
            if (sb.length() > 0) {
                sb.append("&");
            }
            sb.append(e.getKey()).append("=").append(e.getValue() == null ? "" : e.getValue());
        }
        return sb.toString();
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
