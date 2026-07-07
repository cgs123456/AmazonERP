package com.amz.agent;

import com.amz.model.dto.AgentChatDto;
import com.amz.result.Result;
import com.amz.service.AiService;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * ERP 运营 Agent 编排服务。
 *
 * 编排流程（最多 5 轮）：
 * 1. 构建系统提示词（工具清单 + Few-Shot）
 * 2. 调用 LLM（AiService.agentChat）
 * 3. 解析 LLM 输出是否为 Function Call
 * 4. 如果是 → 执行工具 → 将结果注入消息 → 回到步骤 2
 * 5. 如果不是（自然语言） → 返回给用户
 */
@Slf4j
@Service
public class ErpAgentService {

    private static final int MAX_ROUNDS = 5;

    @Autowired
    private AiService aiService;

    @Autowired
    private ErpToolExecutor toolExecutor;

    @Autowired
    private ErpSystemPromptBuilder promptBuilder;

    private final Gson gson = new Gson();

    /**
     * 执行 Agent 对话
     * @param userMessage 用户输入
     * @return Agent 最终回复
     */
    public Result<String> chat(String userMessage) {
        String systemPrompt = promptBuilder.build();
        List<AgentChatDto.Message> messages = new ArrayList<>();

        // 用户消息
        AgentChatDto.Message userMsg = new AgentChatDto.Message();
        userMsg.setRole("user");
        userMsg.setContent(userMessage);
        messages.add(userMsg);

        for (int round = 1; round <= MAX_ROUNDS; round++) {
            // 调用 LLM
            AgentChatDto chatDto = new AgentChatDto();
            chatDto.setSystemPrompt(round == 1 ? systemPrompt : null);
            chatDto.setMessages(messages);
            Result<String> llmResult = aiService.agentChat(chatDto);

            if (llmResult.getCode() != 200) {
                return Result.failure("LLM 调用失败: " + llmResult.getMessage());
            }

            String llmOutput = llmResult.getData();

            // 尝试解析为 Function Call
            FunctionCall call = parseFunctionCall(llmOutput);
            if (call == null) {
                // 自然语言回复 → 返回给用户
                log.info("Agent 第{}轮返回自然语言", round);
                return Result.success(llmOutput);
            }

            // 执行工具
            log.info("Agent 第{}轮调用工具: {}", round, call.getName());
            String toolResult = toolExecutor.execute(call);

            // 将 LLM 输出作为 assistant 消息
            AgentChatDto.Message assistantMsg = new AgentChatDto.Message();
            assistantMsg.setRole("assistant");
            assistantMsg.setContent(llmOutput);
            messages.add(assistantMsg);

            // 将工具结果作为 user 消息注入
            AgentChatDto.Message toolMsg = new AgentChatDto.Message();
            toolMsg.setRole("user");
            toolMsg.setContent("工具执行结果：" + toolResult + "\n请根据结果回答用户问题。");
            messages.add(toolMsg);
        }

        return Result.success("已达到最大对话轮数，请稍后重试。");
    }

    /**
     * 解析 LLM 输出是否为 Function Call（JSON 格式）。
     */
    private FunctionCall parseFunctionCall(String text) {
        if (text == null || text.trim().isEmpty()) return null;
        String trimmed = text.trim();

        // 尝试提取 JSON
        String json = extractJson(trimmed);
        if (json == null) return null;

        try {
            JsonObject obj = gson.fromJson(json, JsonObject.class);
            if (!obj.has("name")) return null;

            FunctionCall call = new FunctionCall();
            call.setName(obj.get("name").getAsString());
            if (obj.has("arguments") && obj.get("arguments").isJsonObject()) {
                call.setArguments(gson.fromJson(obj.get("arguments"), java.util.Map.class));
            }
            return call;
        } catch (Exception e) {
            return null;
        }
    }

    private String extractJson(String text) {
        // 尝试 ```json 块
        int start = text.indexOf("```json");
        if (start >= 0) {
            int end = text.indexOf("```", start + 7);
            if (end > start) return text.substring(start + 7, end).trim();
        }
        // 尝试裸 ``` 块
        start = text.indexOf("```");
        if (start >= 0) {
            int end = text.indexOf("```", start + 3);
            if (end > start) {
                String candidate = text.substring(start + 3, end).trim();
                if (candidate.startsWith("{")) return candidate;
            }
        }
        // 尝试整段
        if (text.startsWith("{") && text.endsWith("}")) return text;
        return null;
    }
}
