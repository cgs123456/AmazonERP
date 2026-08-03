package com.amz.client.impl;

import com.amz.client.MessagingApiClient;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Amazon SP-API Messaging 真实客户端（骨架+优雅降级）。
 * <p>
 * API: /messaging/v1/orders/{amazonOrderId}/messages
 * <p>
 * 需要 LWA access token（通过 amz-service-spapi 获取），当前使用缓存 token 降级。
 */
@Slf4j
@Component
@Profile("!mock")
public class MessagingApiRealClient implements MessagingApiClient {

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private static final Gson GSON = new Gson();

    @Value("${spapi.messaging.endpoint:https://sellingpartnerapi.amazon.com}")
    private String apiEndpoint;

    @Value("${spapi.lwa.access-token:}")
    private String accessToken;

    private final ConcurrentHashMap<String, String> tokenCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> tokenExpiry = new ConcurrentHashMap<>();

    @Override
    public List<Map<String, Object>> listMessages(Long shopId, String marketplaceId, int pageSize) {
        try {
            String token = getToken("shop:" + shopId);
            String url = String.format("%s/messaging/v1/orders?marketplaceIds=%s&pageSize=%d", apiEndpoint, marketplaceId, pageSize);
            HttpRequest req = buildRequest(url, token);
            HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) {
                JsonObject body = GSON.fromJson(resp.body(), JsonObject.class);
                return GSON.fromJson(body.getAsJsonArray("messages"), new TypeToken<List<Map<String, Object>>>(){}.getType());
            }
            if (resp.statusCode() == 401 || resp.statusCode() == 403) {
                tokenCache.remove("shop:" + shopId);
            }
            log.warn("Messaging API listMessages HTTP {} shopId={}", resp.statusCode(), shopId);
        } catch (Exception e) {
            log.error("Messaging API listMessages failed shopId={}", shopId, e);
        }
        return Collections.emptyList();
    }

    @Override
    public Map<String, Object> getMessage(Long shopId, String messageId) {
        try {
            String token = getToken("shop:" + shopId);
            String url = String.format("%s/messaging/v1/messages/%s", apiEndpoint, messageId);
            HttpRequest req = buildRequest(url, token);
            HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) {
                return GSON.fromJson(resp.body(), new TypeToken<Map<String, Object>>(){}.getType());
            }
        } catch (Exception e) {
            log.error("Messaging API getMessage failed messageId={}", messageId, e);
        }
        return Collections.emptyMap();
    }

    @Override
    public boolean replyMessage(Long shopId, String messageId, String body) {
        try {
            String token = getToken("shop:" + shopId);
            String url = String.format("%s/messaging/v1/messages/%s/reply", apiEndpoint, messageId);
            Map<String, String> payload = Map.of("body", body, "contentType", "text/plain");
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", "Bearer " + token)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(payload)))
                    .timeout(Duration.ofSeconds(15))
                    .build();
            HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            return resp.statusCode() == 200 || resp.statusCode() == 204;
        } catch (Exception e) {
            log.error("Messaging API replyMessage failed messageId={}", messageId, e);
        }
        return false;
    }

    @Override
    public boolean markAsRead(Long shopId, String messageId) {
        try {
            String token = getToken("shop:" + shopId);
            String url = String.format("%s/messaging/v1/messages/%s/read", apiEndpoint, messageId);
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", "Bearer " + token)
                    .PUT(HttpRequest.BodyPublishers.noBody())
                    .timeout(Duration.ofSeconds(10))
                    .build();
            HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            return resp.statusCode() == 200 || resp.statusCode() == 204;
        } catch (Exception e) {
            log.error("Messaging API markAsRead failed messageId={}", messageId, e);
        }
        return false;
    }

    private HttpRequest buildRequest(String url, String token) {
        return HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Bearer " + token)
                .header("x-amz-access-token", token)
                .timeout(Duration.ofSeconds(15))
                .GET()
                .build();
    }

    private String getToken(String key) {
        Long expiry = tokenExpiry.get(key);
        if (expiry != null && System.currentTimeMillis() < expiry - 300000) {
            return tokenCache.get(key);
        }
        if (accessToken != null && !accessToken.isBlank()) {
            tokenCache.put(key, accessToken);
            tokenExpiry.put(key, System.currentTimeMillis() + 3600000);
            return accessToken;
        }
        log.warn("Messaging API access token not configured");
        return "";
    }
}
