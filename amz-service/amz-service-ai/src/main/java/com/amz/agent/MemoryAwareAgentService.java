package com.amz.agent;

import com.amz.model.ConversationMemory;
import com.amz.model.LanguageEnum;
import com.amz.model.UserPreference;
import com.amz.model.dto.AgentChatDto;
import com.amz.result.Result;
import com.amz.service.AiService;
import com.amz.service.MemoryService;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 带记忆 + 多语言的 ERP Agent 编排器。
 * <p>
 * 在原 ErpAgentService 基础上扩展：
 * <ol>
 *   <li>记忆化：从 MemoryService 加载用户偏好和历史对话记忆，自动注入 system prompt</li>
 *   <li>偏好提取：每次对话后从用户输入中提取偏好信号并更新</li>
 *   <li>多语言：根据用户语言偏好构建不同语言的系统提示词</li>
 *   <li>对话持久化：每轮 user/assistant 消息保存到 amz_conversation_memory 表</li>
 * </ol>
 * 编排流程与 ErpAgentService 一致（最多 5 轮 Function Calling）。
 */
@Slf4j
@Service
public class MemoryAwareAgentService {

    private static final int MAX_ROUNDS = 5;

    /** 历史记忆注入条数（取最近 6 条以避免上下文过长） */
    private static final int MEMORY_INJECT_LIMIT = 6;

    @Autowired
    private AiService aiService;

    @Autowired
    private ErpToolExecutor toolExecutor;

    @Autowired
    private MultiLangPromptBuilder promptBuilder;

    @Autowired
    private MemoryService memoryService;

    private final Gson gson = new Gson();

    /**
     * 带记忆的 Agent 对话。
     *
     * @param userId      用户 ID
     * @param userMessage 用户输入
     * @return Agent 最终回复
     */
    public Result<String> chat(Long userId, String userMessage) {
        // 1. 加载 / 提取偏好
        memoryService.extractAndUpdatePreference(userId, userMessage);
        UserPreference pref = memoryService.getOrCreatePreference(userId);
        LanguageEnum lang = LanguageEnum.fromCode(pref.getLanguage());

        // 2. 生成 sessionId 并加载历史记忆
        String sessionId = "sess-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        List<ConversationMemory> history = memoryService.listRecentMemories(
                "sess-last-" + userId, MEMORY_INJECT_LIMIT);

        // 3. 构建系统提示词（含偏好上下文 + 语言指令）
        String systemPrompt = buildContextualPrompt(pref, lang, history);

        // 4. 保存当前用户消息到记忆
        memoryService.saveMemory(sessionId, userId, "user", userMessage);
        memoryService.touchUserActive(userId);

        // 5. 多轮 Function Calling 编排
        List<AgentChatDto.Message> messages = new ArrayList<>();
        AgentChatDto.Message userMsg = new AgentChatDto.Message();
        userMsg.setRole("user");
        userMsg.setContent(userMessage);
        messages.add(userMsg);

        for (int round = 1; round <= MAX_ROUNDS; round++) {
            AgentChatDto chatDto = new AgentChatDto();
            chatDto.setSystemPrompt(round == 1 ? systemPrompt : null);
            chatDto.setMessages(messages);
            Result<String> llmResult = aiService.agentChat(chatDto);

            if (llmResult.getCode() != 200) {
                return Result.failure("LLM 调用失败: " + llmResult.getMessage());
            }

            String llmOutput = llmResult.getData();
            FunctionCall call = parseFunctionCall(llmOutput);
            if (call == null) {
                // 自然语言回复 → 保存记忆并返回
                memoryService.saveMemory(sessionId, userId, "assistant", llmOutput);
                log.info("Agent（记忆化）第{}轮返回自然语言，lang={}", round, lang);
                return Result.success(llmOutput);
            }

            // Function Call → 执行工具
            log.info("Agent（记忆化）第{}轮调用工具: {}", round, call.getName());
            String toolResult = toolExecutor.execute(call);

            // 在 Function Call 场景下，用户偏好中的 shopId 可作为默认值注入下一轮
            injectDefaultShopIdIfNeeded(call, pref);

            AgentChatDto.Message assistantMsg = new AgentChatDto.Message();
            assistantMsg.setRole("assistant");
            assistantMsg.setContent(llmOutput);
            messages.add(assistantMsg);

            AgentChatDto.Message toolMsg = new AgentChatDto.Message();
            toolMsg.setRole("user");
            toolMsg.setContent("工具执行结果：" + toolResult + "\n请根据结果回答用户问题，使用"
                    + lang.getDisplayName() + "回复。");
            messages.add(toolMsg);
        }

        return Result.success("已达到最大对话轮数，请稍后重试。");
    }

    /**
     * 构建带用户偏好上下文 + 历史记忆的系统提示词。
     */
    private String buildContextualPrompt(UserPreference pref, LanguageEnum lang,
                                         List<ConversationMemory> history) {
        StringBuilder sb = new StringBuilder();
        sb.append(promptBuilder.build(lang));

        sb.append("\n\n【用户偏好上下文】\n");
        sb.append("- 用户 ID: ").append(pref.getUserId()).append("\n");
        sb.append("- 默认店铺 ID: ").append(pref.getPreferredShopId());
        if (pref.getPreferredShopName() != null) {
            sb.append("（").append(pref.getPreferredShopName()).append("）");
        }
        sb.append("\n");
        if (pref.getPreferredCategory() != null) {
            sb.append("- 关注品类: ").append(pref.getPreferredCategory()).append("\n");
        }
        sb.append("- 回复语言: ").append(lang.getDisplayName()).append("\n");
        sb.append("当用户未显式指定 shopId 时，使用上述默认店铺 ID。\n");

        if (history != null && !history.isEmpty()) {
            sb.append("\n【历史对话摘要】\n");
            for (ConversationMemory m : history) {
                sb.append("- ").append(m.getRole()).append(": ")
                  .append(truncate(m.getContent(), 80)).append("\n");
            }
        }
        return sb.toString();
    }

    /**
     * 如果用户未在 Function Call 中指定 shopId，注入偏好店铺。
     */
    private void injectDefaultShopIdIfNeeded(FunctionCall call, UserPreference pref) {
        if (call.getArguments() == null) {
            call.setArguments(new java.util.HashMap<>());
        }
        if (!call.getArguments().containsKey("shopId") && pref.getPreferredShopId() != null) {
            call.getArguments().put("shopId", pref.getPreferredShopId());
        }
    }

    private String truncate(String text, int max) {
        if (text == null) return "";
        return text.length() > max ? text.substring(0, max) + "..." : text;
    }

    private FunctionCall parseFunctionCall(String text) {
        if (text == null || text.trim().isEmpty()) return null;
        String trimmed = text.trim();
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
        int start = text.indexOf("```json");
        if (start >= 0) {
            int end = text.indexOf("```", start + 7);
            if (end > start) return text.substring(start + 7, end).trim();
        }
        start = text.indexOf("```");
        if (start >= 0) {
            int end = text.indexOf("```", start + 3);
            if (end > start) {
                String candidate = text.substring(start + 3, end).trim();
                if (candidate.startsWith("{")) return candidate;
            }
        }
        if (text.startsWith("{") && text.endsWith("}")) return text;
        return null;
    }
}
