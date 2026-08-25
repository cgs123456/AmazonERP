package com.amz.agent.selection;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;

/**
 * 选品分析服务实现：调用 DeepSeek 生成选品建议。
 * <p>
 * Prompt 包含：ASIN、市场指标、竞争指标、趋势。
 * 返回：ai_summary（摘要）+ ai_suggestion（详细建议：是否进入/定价策略/差异化方向/首批采购量）。
 */
@Slf4j
@Service
public class SelectionAnalysisServiceImpl implements SelectionAnalysisService {

    @Value("${deepseek.api_url}")
    private String apiUrl;

    @Value("${deepseek.api_key}")
    private String apiKey;

    /** 共享 OkHttp 客户端（见 AiHttpClientConfig）。 */
    @Autowired
    private OkHttpClient client;

    private final Gson gson = new Gson();

    @Override
    public SelectionAnalysisResult analyzeOpportunity(SelectionOpportunityInput input) {
        if (input == null) {
            SelectionAnalysisResult empty = new SelectionAnalysisResult();
            empty.setAiSummary("无选品数据可分析");
            empty.setAiSuggestion("请提供完整的选品机会数据后重试。");
            return empty;
        }

        String prompt = buildPrompt(input);
        String llmResponse = callDeepSeek(prompt);
        return parseResult(llmResponse);
    }

    /**
     * 构建选品分析提示词，要求 LLM 返回严格 JSON。
     */
    private String buildPrompt(SelectionOpportunityInput input) {
        return "你是 Amazon 选品专家，请基于以下选品机会数据生成分析建议。输出严格的 JSON（不要包含 markdown 代码块标记），格式如下：\n" +
                "{\n" +
                "  \"aiSummary\": \"一段话总结该机会的市场容量、竞争程度和趋势\",\n" +
                "  \"aiSuggestion\": \"详细建议，包含：(1)是否进入市场（建议/谨慎/不推荐）+ 理由；(2)定价策略；(3)差异化方向（产品/Listing/广告）；(4)首批采购量建议\"\n" +
                "}\n\n" +
                "选品机会数据：\n" +
                "- ASIN: " + nullSafe(input.getAsin()) + "\n" +
                "- 标题: " + nullSafe(input.getTitle()) + "\n" +
                "- 品类: " + nullSafe(input.getCategory()) + "\n" +
                "- 站点: " + nullSafe(input.getMarketplace()) + "\n" +
                "- 平均售价: $" + nullSafe(input.getAvgPrice()) + "\n" +
                "- 平均评论数: " + nullSafe(input.getAvgReviews()) + "\n" +
                "- 平均评分: " + nullSafe(input.getAvgRating()) + "\n" +
                "- 月搜索量: " + nullSafe(input.getSearchVolume()) + "\n" +
                "- 竞品数量: " + nullSafe(input.getCompetitorCount()) + "\n" +
                "- 评论壁垒: " + nullSafe(input.getReviewBarrier()) + "\n" +
                "- 机会评分(0-100): " + nullSafe(input.getOpportunityScore()) + "\n" +
                "- 30天趋势: " + nullSafe(input.getTrend30d()) + "\n" +
                "- 90天趋势: " + nullSafe(input.getTrend90d());
    }

    /**
     * 调用 DeepSeek Chat Completions API。
     */
    private String callDeepSeek(String prompt) {
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("DeepSeek API key 未配置，跳过 AI 选品分析");
            return null;
        }

        JsonObject requestBody = new JsonObject();
        requestBody.addProperty("model", "deepseek-chat");

        JsonArray messages = new JsonArray();
        JsonObject userMessage = new JsonObject();
        userMessage.addProperty("role", "user");
        userMessage.addProperty("content", prompt);
        messages.add(userMessage);
        requestBody.add("messages", messages);
        // 降低随机性，使 JSON 输出更稳定
        requestBody.addProperty("temperature", 0.4);

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
     * 解析 LLM 返回的 JSON 内容为 SelectionAnalysisResult。
     * 容错处理：LLM 可能返回带 markdown 代码块标记的 JSON。
     */
    private SelectionAnalysisResult parseResult(String llmResponse) {
        SelectionAnalysisResult result = new SelectionAnalysisResult();
        if (llmResponse == null || llmResponse.isBlank()) {
            result.setAiSummary("LLM 未返回结果");
            result.setAiSuggestion("DeepSeek API 未配置或调用失败，请检查 DEEPSEEK_API_KEY 环境变量。");
            return result;
        }

        try {
            // 去除可能的 markdown 代码块标记
            String json = llmResponse.trim();
            if (json.startsWith("```")) {
                json = json.replaceAll("^```(json)?\\s*", "").replaceAll("\\s*```$", "");
            }

            JsonObject obj = gson.fromJson(json, JsonObject.class);
            result.setAiSummary(obj.has("aiSummary")
                    ? obj.get("aiSummary").getAsString()
                    : "无摘要");
            result.setAiSuggestion(obj.has("aiSuggestion")
                    ? obj.get("aiSuggestion").getAsString()
                    : "无详细建议");
        } catch (Exception e) {
            log.error("解析 LLM 选品分析结果失败", e);
            result.setAiSummary("解析失败");
            result.setAiSuggestion("LLM 返回内容解析失败: "
                    + llmResponse.substring(0, Math.min(llmResponse.length(), 200)));
        }
        return result;
    }

    private String nullSafe(Object obj) {
        return obj == null ? "未知" : obj.toString();
    }
}
