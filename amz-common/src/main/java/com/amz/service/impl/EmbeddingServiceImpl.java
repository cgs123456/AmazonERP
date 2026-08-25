package com.amz.service.impl;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.amz.service.EmbeddingService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * EmbeddingService 实现：调用 OpenAI 兼容的 /v1/embeddings 接口。
 * 使用 JDK HttpClient + Gson（与 amz-common 现有风格一致）。
 * 默认禁用，需通过 embedding.enabled=true 开启。
 */
@Service
@Slf4j
public class EmbeddingServiceImpl implements EmbeddingService {

    @Value("${embedding.enabled:false}")
    private boolean enabled;

    @Value("${embedding.api_url:https://api.openai.com/v1}")
    private String apiUrl;

    @Value("${embedding.api_key:}")
    private String apiKey;

    @Value("${embedding.model:text-embedding-v3}")
    private String model;

    private final Gson gson = new Gson();

    private static final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    // ==================== 查询向量本地缓存 ====================

    /** 缓存最大条目数：超出后整体清空（简单防膨胀，热搜/重复查询场景命中率已足够）。 */
    private static final int EMBED_CACHE_MAX = 512;

    /** 缓存 TTL：10 分钟。 */
    private static final long EMBED_CACHE_TTL_MS = 10 * 60 * 1000L;

    private final java.util.concurrent.ConcurrentHashMap<String, EmbedCacheEntry> embedCache =
            new java.util.concurrent.ConcurrentHashMap<>();

    private static final class EmbedCacheEntry {
        final float[] vector;
        final long expiresAtMs;

        EmbedCacheEntry(float[] vector, long expiresAtMs) {
            this.vector = vector;
            this.expiresAtMs = expiresAtMs;
        }
    }

    @Override
    public float[] embed(String text) {
        if (!isAvailable() || text == null || text.trim().isEmpty()) {
            return null;
        }
        String normalized = text.trim().toLowerCase();
        long now = System.currentTimeMillis();
        EmbedCacheEntry cached = embedCache.get(normalized);
        if (cached != null && cached.expiresAtMs > now) {
            // 返回克隆，避免调用方修改缓存值
            return cached.vector.clone();
        }
        float[] vector = embedRemote(text);
        if (vector != null) {
            if (embedCache.size() >= EMBED_CACHE_MAX) {
                embedCache.clear();
            }
            embedCache.put(normalized, new EmbedCacheEntry(vector, now + EMBED_CACHE_TTL_MS));
            return vector.clone();
        }
        return null;
    }

    /**
     * 实际远程调用 /v1/embeddings。
     */
    private float[] embedRemote(String text) {
        try {
            // 构建请求体（使用 Gson 构建，避免手动转义问题）
            JsonObject reqObj = new JsonObject();
            reqObj.addProperty("model", model);
            reqObj.addProperty("input", text);
            reqObj.addProperty("encoding_format", "float");
            String requestBody = gson.toJson(reqObj);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(apiUrl + "/embeddings"))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(15))
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                JsonObject resp = gson.fromJson(response.body(), JsonObject.class);
                JsonArray embeddingArr = resp.getAsJsonArray("data")
                        .get(0).getAsJsonObject()
                        .get("embedding").getAsJsonArray();
                return parseFloatArray(embeddingArr);
            } else {
                log.error("Embedding 请求失败: {} - {}", response.statusCode(), response.body());
                return null;
            }
        } catch (Exception e) {
            log.error("Embedding 调用异常", e);
            return null;
        }
    }

    @Override
    public boolean isAvailable() {
        return enabled && apiKey != null && !apiKey.trim().isEmpty();
    }

    private float[] parseFloatArray(JsonArray arr) {
        float[] result = new float[arr.size()];
        for (int i = 0; i < arr.size(); i++) {
            result[i] = (float) arr.get(i).getAsDouble();
        }
        return result;
    }
}
