package com.amz.agent.review;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 评论分析服务实现：调用 DeepSeek LLM 进行痛点聚类、情感分析和改进建议生成。
 */
@Slf4j
@Service
public class ReviewAnalysisServiceImpl implements ReviewAnalysisService {

    @Value("${deepseek.api_url}")
    private String apiUrl;

    @Value("${deepseek.api_key}")
    private String apiKey;

    private final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build();

    private final Gson gson = new Gson();

    @Override
    public ReviewAnalysisResult analyze(List<ReviewInfo> reviews) {
        if (reviews == null || reviews.isEmpty()) {
            ReviewAnalysisResult empty = new ReviewAnalysisResult();
            empty.setSentimentScore(0.0);
            empty.setPainPoints(Collections.emptyList());
            empty.setSuggestions(Collections.emptyList());
            empty.setSummary("无评论数据可分析");
            return empty;
        }

        String prompt = buildPrompt(reviews);
        String llmResponse = callDeepSeek(prompt);
        return parseResult(llmResponse, reviews);
    }

    /**
     * 构建评论分析提示词，要求 LLM 返回严格 JSON。
     */
    private String buildPrompt(List<ReviewInfo> reviews) {
        String reviewsText = reviews.stream()
                .map(r -> {
                    String vp = r.getVerifiedPurchase() != null && r.getVerifiedPurchase() ? "VP" : "非VP";
                    return String.format("- 评分%d星 | %s | 日期:%s | %s\n  标题:%s\n  内容:%s",
                            r.getRating() != null ? r.getRating() : 0,
                            vp,
                            r.getDate() != null ? r.getDate() : "未知",
                            r.getTitle() != null ? r.getTitle() : "",
                            r.getTitle() != null ? r.getTitle() : "",
                            r.getContent() != null ? r.getContent() : "");
                })
                .collect(Collectors.joining("\n"));

        return "你是 Amazon 商品评论分析专家。请分析以下评论，输出严格的 JSON（不要包含 markdown 代码块标记），格式如下：\n" +
                "{\n" +
                "  \"sentimentScore\": 0-100的整数，越高越正面,\n" +
                "  \"painPoints\": [\"痛点1\", \"痛点2\"],\n" +
                "  \"suggestions\": [\"改进建议1\", \"改进建议2\"],\n" +
                "  \"summary\": \"一段话总结评论整体情况\"\n" +
                "}\n\n" +
                "评论列表（共" + reviews.size() + "条）：\n" + reviewsText;
    }

    /**
     * 调用 DeepSeek Chat Completions API。
     */
    private String callDeepSeek(String prompt) {
        JsonObject requestBody = new JsonObject();
        requestBody.addProperty("model", "deepseek-chat");

        JsonArray messages = new JsonArray();
        JsonObject userMessage = new JsonObject();
        userMessage.addProperty("role", "user");
        userMessage.addProperty("content", prompt);
        messages.add(userMessage);
        requestBody.add("messages", messages);
        // 降低随机性，使 JSON 输出更稳定
        requestBody.addProperty("temperature", 0.3);

        RequestBody body = RequestBody.create(
                requestBody.toString(),
                MediaType.parse("application/json; charset=utf-8")
        );

        Request request = new Request.Builder()
                .url(apiUrl + "/chat/completions")
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .post(body)
                .build();

        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                log.error("DeepSeek API 调用失败: {}", response.code());
                return null;
            }
            String responseBody = response.body() != null ? response.body().string() : "";
            JsonObject jsonResponse = gson.fromJson(responseBody, JsonObject.class);
            JsonArray choices = jsonResponse.getAsJsonArray("choices");
            return choices.get(0).getAsJsonObject()
                    .getAsJsonObject("message")
                    .get("content").getAsString();
        } catch (IOException e) {
            log.error("DeepSeek API 调用异常", e);
            return null;
        }
    }

    /**
     * 解析 LLM 返回的 JSON 内容为 ReviewAnalysisResult。
     * 容错处理：LLM 可能返回带 markdown 代码块标记的 JSON。
     */
    private ReviewAnalysisResult parseResult(String llmResponse, List<ReviewInfo> reviews) {
        ReviewAnalysisResult result = new ReviewAnalysisResult();
        if (llmResponse == null || llmResponse.isBlank()) {
            result.setSentimentScore(0.0);
            result.setPainPoints(Collections.emptyList());
            result.setSuggestions(Collections.emptyList());
            result.setSummary("LLM 分析失败，未返回结果");
            return result;
        }

        try {
            // 去除可能的 markdown 代码块标记
            String json = llmResponse.trim();
            if (json.startsWith("```")) {
                json = json.replaceAll("^```(json)?\\s*", "").replaceAll("\\s*```$", "");
            }

            JsonObject obj = gson.fromJson(json, JsonObject.class);
            result.setSentimentScore(obj.has("sentimentScore")
                    ? obj.get("sentimentScore").getAsDouble()
                    : 0.0);
            result.setPainPoints(parseStringArray(obj, "painPoints"));
            result.setSuggestions(parseStringArray(obj, "suggestions"));
            result.setSummary(obj.has("summary")
                    ? obj.get("summary").getAsString()
                    : "");
        } catch (Exception e) {
            log.error("解析 LLM 评论分析结果失败", e);
            result.setSentimentScore(0.0);
            result.setPainPoints(Collections.emptyList());
            result.setSuggestions(Collections.emptyList());
            result.setSummary("LLM 返回内容解析失败: " + llmResponse.substring(0, Math.min(llmResponse.length(), 200)));
        }
        return result;
    }

    private List<String> parseStringArray(JsonObject obj, String key) {
        if (!obj.has(key) || !obj.get(key).isJsonArray()) {
            return Collections.emptyList();
        }
        JsonArray arr = obj.getAsJsonArray(key);
        List<String> result = new ArrayList<>();
        for (int i = 0; i < arr.size(); i++) {
            result.add(arr.get(i).getAsString());
        }
        return result;
    }
}
