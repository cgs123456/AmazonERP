package com.amz.client;

import com.amz.auth.AwsSigV4Signer;
import com.amz.auth.LwaTokenManager;
import com.amz.credential.ShopCredential;
import com.amz.credential.ShopCredentialStore;
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
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Amazon SP-API FBA Inventory v1 客户端。
 * <p>
 * 调用 /fba/inventory/v1/summaries 接口拉取 FBA 库存汇总。
 * 实现与 {@link OrdersClient} 一致的风格：LWA Token + Sig V4 签名 + 429 重试 + NextToken 分页。
 * 在此基础上额外实现滑动窗口限流：30 秒内最多 25 次请求，超过则阻塞等待。
 */
@Component
public class FbaInventoryClient {

    private static final Logger log = LoggerFactory.getLogger(FbaInventoryClient.class);

    private static final String INVENTORY_PATH = "/fba/inventory/v1/summaries";

    /**
     * 429 限流重试次数上限。
     */
    private static final int MAX_RETRIES = 3;

    /**
     * SP-API 各区域端点（NA/EU/FE）。
     */
    private static final Map<String, String> SPAPI_ENDPOINTS = Map.of(
            "NA", "https://sellingpartnerapi-na.amazon.com",
            "EU", "https://sellingpartnerapi-eu.amazon.com",
            "FE", "https://sellingpartnerapi-fe.amazon.com"
    );

    /**
     * 常见 Marketplace ID 到区域（NA/EU/FE）的映射。
     */
    private static final Map<String, String> MARKETPLACE_REGION = Map.ofEntries(
            Map.entry("ATVPDKIKX0DER", "NA"),  // 美国
            Map.entry("A2EUQ1WTGCTBG2", "NA"),  // 加拿大
            Map.entry("A1AM78C64UM0Y8", "NA"),  // 墨西哥
            Map.entry("A1F83G8C2ARO7P", "EU"),  // 英国
            Map.entry("A13V1IB3VIYZZH", "EU"),  // 法国
            Map.entry("A1PA6795UKMFR9", "EU"),  // 德国
            Map.entry("A1RKKUPIHCS9HS", "EU"),  // 西班牙
            Map.entry("APJ6JRA9NG5V4", "EU"),   // 意大利
            Map.entry("A39IBJ37TRP1C6", "FE"),  // 澳大利亚
            Map.entry("A1VC38T7YXB528", "FE")   // 日本
    );

    // ==================== 滑动窗口限流配置 ====================

    /**
     * 限流窗口大小：30 秒。
     */
    private static final Duration THROTTLE_WINDOW = Duration.ofSeconds(30);

    /**
     * 窗口内最大请求数：25 次。
     */
    private static final int THROTTLE_MAX_REQUESTS = 25;

    /**
     * 请求时间戳队列，用于实现滑动窗口限流。
     * 通过 synchronized 方法保证线程安全。
     */
    private final Deque<Instant> requestTimestamps = new ArrayDeque<>();

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    @Autowired
    private LwaTokenManager lwaTokenManager;

    @Autowired
    private AwsSigV4Signer awsSigV4Signer;

    @Autowired
    private ShopCredentialStore shopCredentialStore;

    /**
     * 拉取指定店铺在某 marketplace 下的全量 FBA 库存汇总。
     * 自动分页拉取所有 NextToken，details=true & granularityType=Marketplace。
     *
     * @param shopId        店铺 ID
     * @param marketplaceId Amazon Marketplace ID
     * @return 库存原始 JSON 列表（每条为 payload.inventorySummaries 数组中的一个对象）
     */
    public List<JsonObject> fetchAllInventory(Long shopId, String marketplaceId) {
        ShopCredential credential = shopCredentialStore.get(shopId);
        if (credential == null) {
            throw new IllegalArgumentException("No credential found for shopId=" + shopId);
        }

        String region = mapMarketplaceToRegion(marketplaceId);
        String endpoint = SPAPI_ENDPOINTS.get(region);
        if (endpoint == null) {
            throw new IllegalArgumentException("No SP-API endpoint for region=" + region);
        }

        String host = endpoint.replace("https://", "");
        String accessToken = lwaTokenManager.getToken(credential);

        List<JsonObject> allItems = new ArrayList<>();
        String nextToken = null;
        int pageCount = 0;

        do {
            // 发请求前先做滑动窗口限流
            throttleIfNeeded();

            TreeMap<String, String> params = new TreeMap<>();
            params.put("details", "true");
            params.put("granularityType", "Marketplace");
            params.put("granularityId", marketplaceId);
            params.put("marketplaceIds", marketplaceId);
            if (nextToken != null) {
                params.put("nextToken", nextToken);
            }
            String queryString = buildCanonicalQueryString(params);

            Map<String, String> signedHeaders = awsSigV4Signer.sign(
                    "GET", host, INVENTORY_PATH, queryString, "",
                    credential.getAccessKey(), credential.getSecretKey(), region);

            String url = endpoint + INVENTORY_PATH + "?" + queryString;
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(30))
                    .GET();
            signedHeaders.forEach(builder::header);
            // LWA access_token 通过 x-amz-access-token 透传，不参与 Sig V4 签名
            builder.header("x-amz-access-token", accessToken);

            HttpResponse<String> response = sendWithRetry(builder.build());
            if (response == null || response.statusCode() != 200) {
                log.error("fetchAllInventory failed shopId={} status={} body={}", shopId,
                        response == null ? -1 : response.statusCode(),
                        response == null ? "" : response.body());
                break;
            }

            JsonObject body = JsonParser.parseString(response.body()).getAsJsonObject();
            JsonObject payload = body.has("payload") && body.get("payload").isJsonObject()
                    ? body.getAsJsonObject("payload") : body;
            if (payload.has("inventorySummaries") && payload.get("inventorySummaries").isJsonArray()) {
                for (JsonElement e : payload.getAsJsonArray("inventorySummaries")) {
                    allItems.add(e.getAsJsonObject());
                }
            }
            nextToken = (payload.has("nextToken") && !payload.get("nextToken").isJsonNull())
                    ? payload.get("nextToken").getAsString() : null;
            pageCount++;
            log.debug("fetchAllInventory shopId={} page={} collected={} hasNext={}",
                    shopId, pageCount, allItems.size(), nextToken != null);
        } while (nextToken != null);

        log.info("fetchAllInventory done shopId={} pages={} total={}", shopId, pageCount, allItems.size());
        return allItems;
    }

    /**
     * 滑动窗口限流：保证 30 秒内最多 25 次请求，超过则 Thread.sleep 等待。
     * <p>
     * 通过 synchronized 保证多线程下窗口统计的一致性。
     */
    private synchronized void throttleIfNeeded() {
        Instant now = Instant.now();
        Instant windowStart = now.minus(THROTTLE_WINDOW);
        // 移除窗口外的旧时间戳
        while (!requestTimestamps.isEmpty() && requestTimestamps.peekFirst().isBefore(windowStart)) {
            requestTimestamps.pollFirst();
        }
        if (requestTimestamps.size() >= THROTTLE_MAX_REQUESTS) {
            Instant oldest = requestTimestamps.peekFirst();
            long sleepMs = Duration.between(now, oldest.plus(THROTTLE_WINDOW)).toMillis();
            if (sleepMs > 0) {
                log.info("throttleIfNeeded: window full, sleeping {}ms", sleepMs);
                try {
                    Thread.sleep(sleepMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            // 睡醒后再次清理过期时间戳
            Instant after = Instant.now();
            Instant newWindowStart = after.minus(THROTTLE_WINDOW);
            while (!requestTimestamps.isEmpty() && requestTimestamps.peekFirst().isBefore(newWindowStart)) {
                requestTimestamps.pollFirst();
            }
        }
        requestTimestamps.addLast(Instant.now());
    }

    /**
     * 发送请求，遇到 429 限流时按指数退避重试。
     */
    private HttpResponse<String> sendWithRetry(HttpRequest request) {
        HttpResponse<String> response = null;
        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            try {
                response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
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
                long backoff = (1L << attempt) * 1000L;
                log.warn("Rate limited (429), retrying after {}ms attempt={}", backoff, attempt);
                sleep(backoff);
                continue;
            }
            return response;
        }
        return response;
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 由 TreeMap 保证参数按字典序排列，并做符合 AWS 规范的 URI 编码，
     * 该串同时用于签名与最终 URL。
     */
    private String buildCanonicalQueryString(TreeMap<String, String> params) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : params.entrySet()) {
            if (sb.length() > 0) {
                sb.append("&");
            }
            sb.append(encode(e.getKey())).append("=").append(encode(e.getValue()));
        }
        return sb.toString();
    }

    /**
     * AWS 规范的 URI 编码：空格用 %20，* 用 %2A，~ 保留不编码。
     */
    private String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8)
                .replace("+", "%20")
                .replace("*", "%2A")
                .replace("%7E", "~");
    }

    /**
     * 将 Marketplace ID 映射到 SP-API 区域（NA/EU/FE），未知时默认 NA。
     */
    public String mapMarketplaceToRegion(String marketplaceId) {
        return MARKETPLACE_REGION.getOrDefault(marketplaceId, "NA");
    }
}
