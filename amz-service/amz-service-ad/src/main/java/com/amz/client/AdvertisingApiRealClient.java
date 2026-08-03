package com.amz.client;

import com.amz.model.AdCampaign;
import com.amz.model.AdKeyword;
import com.amz.model.AdReport;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Amazon Advertising API (SP-API Ads) 真实客户端。
 * <p>
 * 仅在 {@code spring.profiles.active} 非 {@code mock} 时生效。
 * <p>
 * 功能：
 * <ul>
 *   <li>LWA Token 获取与缓存（带过期管理）</li>
 *   <li>GET /sp/keywords 拉取关键词</li>
 *   <li>PUT /sp/keywords/{id} 修改竞价</li>
 *   <li>GET /sp/campaigns 拉取广告活动</li>
 *   <li>GET /sp/reports 拉取广告报表</li>
 * </ul>
 * 真实 API 未对接时打 warn 日志降级返回空列表，避免上游抛异常中断流程。
 */
@Slf4j
@Component
@Profile("!mock")
public class AdvertisingApiRealClient implements AdvertisingApiClient {

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private static final Gson GSON = new Gson();

    @Value("${advertising.api-endpoint:https://advertising-api.amazon.com}")
    private String apiEndpoint;

    @Value("${advertising.profile-id:}")
    private String profileId;

    @Value("${spapi.lwa.access-token:}")
    private String accessToken;

    // 缓存：profileId → access token，避免每次请求都刷新
    private final ConcurrentHashMap<String, String> tokenCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> tokenExpiry = new ConcurrentHashMap<>();

    @Override
    public List<AdKeyword> listKeywords(Long shopId, String campaignId) {
        try {
            String token = getAccessToken(shopId);
            String url = String.format("%s/sp/keywords?campaignIdFilter=%s",
                    apiEndpoint, campaignId);
            HttpRequest req = buildRequest(url, token);
            HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());

            if (resp.statusCode() == 200) {
                JsonArray items = GSON.fromJson(resp.body(), JsonArray.class);
                List<AdKeyword> keywords = new ArrayList<>();
                for (int i = 0; i < items.size(); i++) {
                    JsonObject kw = items.get(i).getAsJsonObject();
                    AdKeyword k = new AdKeyword();
                    k.setKeyword(kw.has("keywordText") ? kw.get("keywordText").getAsString() : "");
                    k.setMatchType(kw.has("matchType") ? kw.get("matchType").getAsString() : "");
                    k.setBid(kw.has("bid") ? BigDecimal.valueOf(kw.get("bid").getAsDouble()) : BigDecimal.ZERO);
                    k.setState(kw.has("state") ? kw.get("state").getAsString() : "ENABLED");
                    if (kw.has("keywordId")) {
                        k.setId(kw.get("keywordId").getAsLong());
                    }
                    keywords.add(k);
                }
                log.debug("Advertising API listKeywords 成功 campaignId={} 返回 {} 条", campaignId, keywords.size());
                return keywords;
            }
            if (resp.statusCode() == 401 || resp.statusCode() == 403) {
                invalidateToken(shopId);
            }
            log.warn("Advertising API listKeywords HTTP {} campaignId={}", resp.statusCode(), campaignId);
        } catch (Exception e) {
            log.error("Advertising API listKeywords 失败 campaignId={}", campaignId, e);
        }
        return Collections.emptyList();
    }

    @Override
    public boolean updateKeywordBid(Long keywordId, BigDecimal newBid) {
        try {
            String token = getAccessToken(null);
            String url = String.format("%s/sp/keywords/%d", apiEndpoint, keywordId);
            Map<String, Object> body = new HashMap<>();
            body.put("bid", newBid.doubleValue());
            body.put("state", "enabled");
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", "Bearer " + token)
                    .header("Content-Type", "application/vnd.spkeywords.v3+json")
                    .header("Amazon-Advertising-API-ClientId", profileId)
                    .PUT(HttpRequest.BodyPublishers.ofString(GSON.toJson(body)))
                    .timeout(Duration.ofSeconds(15))
                    .build();
            HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) {
                log.info("Advertising API updateKeywordBid 成功 keywordId={} newBid={}", keywordId, newBid);
                return true;
            }
            log.warn("Advertising API updateKeywordBid HTTP {} keywordId={}", resp.statusCode(), keywordId);
        } catch (Exception e) {
            log.error("Advertising API updateKeywordBid 失败 keywordId={}", keywordId, e);
        }
        return false;
    }

    @Override
    public List<AdCampaign> listCampaigns(Long shopId) {
        try {
            String token = getAccessToken(shopId);
            String url = String.format("%s/sp/campaigns", apiEndpoint);
            HttpRequest req = buildRequest(url, token);
            HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());

            if (resp.statusCode() == 200) {
                JsonArray items = GSON.fromJson(resp.body(), JsonArray.class);
                List<AdCampaign> campaigns = GSON.fromJson(items, new TypeToken<List<AdCampaign>>(){}.getType());
                log.debug("Advertising API listCampaigns 成功 shopId={} 返回 {} 条", shopId, campaigns.size());
                return campaigns;
            }
            if (resp.statusCode() == 401 || resp.statusCode() == 403) {
                invalidateToken(shopId);
            }
            log.warn("Advertising API listCampaigns HTTP {} shopId={}", resp.statusCode(), shopId);
        } catch (Exception e) {
            log.error("Advertising API listCampaigns 失败 shopId={}", shopId, e);
        }
        return Collections.emptyList();
    }

    @Override
    public List<AdReport> getReports(Long shopId, String startDate, String endDate) {
        try {
            String token = getAccessToken(shopId);
            String url = String.format("%s/sp/reports?startDate=%s&endDate=%s",
                    apiEndpoint, startDate, endDate);
            HttpRequest req = buildRequest(url, token);
            HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());

            if (resp.statusCode() == 200) {
                JsonArray items = GSON.fromJson(resp.body(), JsonArray.class);
                List<AdReport> reports = GSON.fromJson(items, new TypeToken<List<AdReport>>(){}.getType());
                log.debug("Advertising API getReports 成功 shopId={} period={}~{} 返回 {} 条",
                        shopId, startDate, endDate, reports.size());
                return reports;
            }
            if (resp.statusCode() == 401 || resp.statusCode() == 403) {
                invalidateToken(shopId);
            }
            log.warn("Advertising API getReports HTTP {} shopId={}", resp.statusCode(), shopId);
        } catch (Exception e) {
            log.error("Advertising API getReports 失败 shopId={} period={}~{}", shopId, startDate, endDate, e);
        }
        return Collections.emptyList();
    }

    private HttpRequest buildRequest(String url, String token) {
        return HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Bearer " + token)
                .header("Amazon-Advertising-API-Scope", profileId)
                .header("Amazon-Advertising-API-ClientId", getClientId())
                .timeout(Duration.ofSeconds(15))
                .GET()
                .build();
    }

    private String getAccessToken(Long shopId) {
        // Use cached token if available and not expired
        String key = shopId != null ? "shop:" + shopId : "default";
        Long expiry = tokenExpiry.get(key);
        if (expiry != null && System.currentTimeMillis() < expiry - 300000) {
            return tokenCache.get(key);
        }
        // In production, call LWA token endpoint via spapi service
        // For now, return configured access token with warning
        if (accessToken != null && !accessToken.isBlank()) {
            tokenCache.put(key, accessToken);
            tokenExpiry.put(key, System.currentTimeMillis() + 3600000);
            return accessToken;
        }
        log.warn("Advertising API access token not configured");
        return "";
    }

    private void invalidateToken(Long shopId) {
        String key = shopId != null ? "shop:" + shopId : "default";
        tokenCache.remove(key);
        tokenExpiry.remove(key);
    }

    private String getClientId() {
        return profileId != null && profileId.contains(":") ? profileId.split(":")[0] : "";
    }
}
