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
 * 使用 JDK HttpClient + Gson（与 redbook-common 现有风格一致）。
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

    @Override
    public float[] embed(String text) {
        if (!isAvailable() || text == null || text.trim().isEmpty()) {
            return null;
        }
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
