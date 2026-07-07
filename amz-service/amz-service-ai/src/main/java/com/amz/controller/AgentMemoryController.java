package com.amz.controller;

import com.amz.agent.MemoryAwareAgentService;
import com.amz.agent.ProactiveReminderService;
import com.amz.model.ConversationMemory;
import com.amz.model.LanguageEnum;
import com.amz.model.UserPreference;
import com.amz.result.Result;
import com.amz.service.MemoryService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Agent 记忆化 / 多语言 / 主动提醒 REST 端点。
 * <p>
 * 提供带记忆的 Agent 对话、用户偏好管理、对话记忆查询、主动提醒扫描等能力。
 */
@RestController
@RequestMapping("/agent/memory")
public class AgentMemoryController {

    @Autowired
    private MemoryAwareAgentService memoryAwareAgentService;

    @Autowired
    private MemoryService memoryService;

    @Autowired
    private ProactiveReminderService proactiveReminderService;

    /**
     * 带记忆 + 多语言的 Agent 对话。
     * POST /agent/memory/chat?userId=1
     * Body: {"message":"最近7天订单如何？店铺1"}
     */
    @PostMapping("/chat")
    public Result<String> chat(@RequestParam Long userId,
                               @RequestBody ChatRequest request) {
        if (request.getMessage() == null || request.getMessage().isBlank()) {
            return Result.failure("message 不能为空");
        }
        return memoryAwareAgentService.chat(userId, request.getMessage());
    }

    /**
     * 查询用户偏好。
     * GET /agent/memory/preference/{userId}
     */
    @GetMapping("/preference/{userId}")
    public Result<UserPreference> getPreference(@PathVariable Long userId) {
        return Result.success(memoryService.getOrCreatePreference(userId));
    }

    /**
     * 更新用户偏好（含语言切换）。
     * POST /agent/memory/preference
     */
    @PostMapping("/preference")
    public Result<UserPreference> updatePreference(@RequestBody UserPreference preference) {
        return Result.success(memoryService.updatePreference(preference));
    }

    /**
     * 切换用户回复语言（便捷端点）。
     * POST /agent/memory/language?userId=1&language=EN
     * language 取值：ZH / EN / JA / DE
     */
    @PostMapping("/language")
    public Result<UserPreference> switchLanguage(@RequestParam Long userId,
                                                 @RequestParam String language) {
        // 校验语言代码合法
        LanguageEnum lang = LanguageEnum.fromCode(language);
        UserPreference pref = memoryService.getOrCreatePreference(userId);
        pref.setLanguage(lang.name());
        return Result.success(memoryService.updatePreference(pref));
    }

    /**
     * 查询用户对话记忆（最近 N 条）。
     * GET /agent/memory/history/{userId}?limit=10
     */
    @GetMapping("/history/{userId}")
    public Result<List<ConversationMemory>> history(@PathVariable Long userId,
                                                    @RequestParam(defaultValue = "10") int limit) {
        return Result.success(memoryService.listRecentMemories(
                "sess-last-" + userId, limit));
    }

    /**
     * 手动触发主动提醒扫描。
     * POST /agent/memory/reminder/scan
     */
    @PostMapping("/reminder/scan")
    public Result<List<String>> scanReminders() {
        return Result.success(proactiveReminderService.scanAndRemind());
    }

    public static class ChatRequest {
        private String message;

        public String getMessage() {
            return message;
        }

        public void setMessage(String message) {
            this.message = message;
        }
    }
}
