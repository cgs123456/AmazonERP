package com.amz.service.impl;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.amz.result.Result;
import com.amz.service.AiService;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.List;

@Slf4j
@Service
public class AiServiceImpl implements AiService {

    @Value("${deepseek.api_url}")
    private String apiUrl;

    @Value("${deepseek.api_key}")
    private String apiKey;

    /** 共享 OkHttp 客户端（连接池/线程池进程内复用，见 AiHttpClientConfig）。 */
    @Autowired
    private OkHttpClient client;

    private final Gson gson = new Gson();

    @Override
    public Result<String> chat(String prompt) {
        JsonObject requestBody = new JsonObject();
        requestBody.addProperty("model", "deepseek-chat");

        JsonArray messages = new JsonArray();
        JsonObject userMessage = new JsonObject();
        userMessage.addProperty("role", "user");
        userMessage.addProperty("content", prompt);
        messages.add(userMessage);
        requestBody.add("messages", messages);

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
                log.warn("DeepSeek API 调用失败: status={}", response.code());
                return Result.failure("DeepSeek API 调用失败: " + response.code());
            }

            String responseBody = response.body() != null ? response.body().string() : "";
            JsonObject jsonResponse = gson.fromJson(responseBody, JsonObject.class);
            JsonArray choices = jsonResponse.getAsJsonArray("choices");
            String content = choices.get(0).getAsJsonObject()
                    .getAsJsonObject("message")
                    .get("content").getAsString();

            return Result.success(content);
        } catch (IOException e) {
            log.error("DeepSeek API 调用异常", e);
            return Result.failure("DeepSeek API 调用异常: " + e.getMessage());
        }
    }

    @Override
    public Result<String> agentChat(com.amz.model.dto.AgentChatDto agentChatDto) {
        // 防御性校验：messages 可能为 null（DTO 未加 @NotNull），直接遍历会 NPE
        List<com.amz.model.dto.AgentChatDto.Message> messages = agentChatDto.getMessages();
        if (messages == null || messages.isEmpty()) {
            return Result.failure("messages 不能为空");
        }

        JsonObject requestBody = new JsonObject();
        requestBody.addProperty("model", "deepseek-chat");

        JsonArray msgArray = new JsonArray();
        // 如果提供了 systemPrompt，作为第一条 system 消息
        if (agentChatDto.getSystemPrompt() != null && !agentChatDto.getSystemPrompt().isEmpty()) {
            JsonObject sysMsg = new JsonObject();
            sysMsg.addProperty("role", "system");
            sysMsg.addProperty("content", agentChatDto.getSystemPrompt());
            msgArray.add(sysMsg);
        }
        // 追加 messages 数组
        for (com.amz.model.dto.AgentChatDto.Message msg : messages) {
            JsonObject m = new JsonObject();
            m.addProperty("role", msg.getRole());
            m.addProperty("content", msg.getContent());
            msgArray.add(m);
        }
        requestBody.add("messages", msgArray);

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
                log.warn("DeepSeek API 调用失败: status={}", response.code());
                return Result.failure("DeepSeek API 调用失败: " + response.code());
            }
            String responseBody = response.body() != null ? response.body().string() : "";
            JsonObject jsonResponse = gson.fromJson(responseBody, JsonObject.class);
            JsonArray choices = jsonResponse.getAsJsonArray("choices");
            String content = choices.get(0).getAsJsonObject()
                    .getAsJsonObject("message")
                    .get("content").getAsString();
            return Result.success(content);
        } catch (IOException e) {
            log.error("DeepSeek API 调用异常", e);
            return Result.failure("DeepSeek API 调用异常: " + e.getMessage());
        }
    }

}