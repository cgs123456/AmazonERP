package com.amz.controller;

import com.amz.agent.MemoryAwareAgentService;
import com.amz.agent.ProactiveReminderService;
import com.amz.annotation.RequireRole;
import com.amz.context.UserContext;
import com.amz.model.ConversationMemory;
import com.amz.model.LanguageEnum;
import com.amz.model.UserPreference;
import com.amz.result.Result;
import com.amz.service.MemoryService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Agent 记忆化 / 多语言 / 主动提醒 REST 端点。
 * <p>
 * 提供带记忆的 Agent 对话、用户偏好管理、对话记忆查询、主动提醒扫描等能力。
 * <p>
 * 类级映射使用 {@code /ai/agent/memory} 而非 {@code /agent/memory}：网关仅配置了
 * {@code Path=/ai/**} 路由到本服务，裸 {@code /agent} 前缀外部不可达。
 * 与 {@code AiController#/ai/agent/chat} 层级不同，不构成 Ambiguous mapping。
 */
@Slf4j
@RestController
@RequestMapping("/ai/agent/memory")
public class AgentMemoryController {

    @Autowired
    private MemoryAwareAgentService memoryAwareAgentService;

    @Autowired
    private MemoryService memoryService;

    @Autowired
    private ProactiveReminderService proactiveReminderService;

    @Autowired
    private Environment environment;

    /**
     * 带记忆 + 多语言的 Agent 对话。
     * POST /agent/memory/chat
     * Body: {"message":"最近7天订单如何？店铺1"}
     */
    @PostMapping("/chat")
    public Result<String> chat(@RequestBody ChatRequest request) {
        if (request.getMessage() == null || request.getMessage().isBlank()) {
            return Result.failure("message 不能为空");
        }
        return memoryAwareAgentService.chat(currentUserId(), request.getMessage());
    }

    /**
     * 查询用户偏好。
     * GET /agent/memory/preference/{userId}
     */
    @GetMapping("/preference/{userId}")
    public Result<UserPreference> getPreference(@PathVariable Long userId) {
        requireSelfOrAdmin(userId);
        return Result.success(memoryService.getOrCreatePreference(userId));
    }

    /**
     * 更新用户偏好（含语言切换）。
     * POST /agent/memory/preference
     */
    @PostMapping("/preference")
    public Result<UserPreference> updatePreference(@RequestBody UserPreference preference) {
        if (preference == null) {
            return Result.failure("preference 不能为空");
        }
        // id 来自客户端时可能指向他人主键；userId 也必须强制绑定认证身份。
        preference.setId(null);
        preference.setUserId(currentUserId());
        return Result.success(memoryService.updatePreference(preference));
    }

    /**
     * 切换用户回复语言（便捷端点）。
     * POST /agent/memory/language?language=EN
     * language 取值：ZH / EN / JA / DE（大小写不敏感）
     * <p>
     * 这里显式拒绝未识别代码，而不是沿用 {@code LanguageEnum#fromCode} 的"未识别即 ZH"：
     * 那个兜底是给提示词渲染用的，而本端点写的是持久化偏好——静默写成 ZH 会让用户
     * 以为切成了请求的语言，且下一次读取再也看不出区别。
     */
    @PostMapping("/language")
    public Result<UserPreference> switchLanguage(@RequestParam String language) {
        long userId = currentUserId();
        LanguageEnum lang = parseLanguage(language);
        if (lang == null) {
            return Result.failure("language 仅支持 " + supportedLanguages()
                    + "，实际收到「" + language + "」");
        }
        UserPreference pref = memoryService.getOrCreatePreference(userId);
        pref.setLanguage(lang.name());
        return Result.success(memoryService.updatePreference(pref));
    }

    private static LanguageEnum parseLanguage(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        try {
            return LanguageEnum.valueOf(code.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String supportedLanguages() {
        return Arrays.stream(LanguageEnum.values()).map(Enum::name).collect(Collectors.joining("/"));
    }

    /**
     * 查询用户对话记忆（最近 N 条）。
     * GET /agent/memory/history/{userId}?limit=10
     */
    @GetMapping("/history/{userId}")
    public Result<List<ConversationMemory>> history(@PathVariable Long userId,
                                                    @RequestParam(defaultValue = "10") int limit) {
        requireSelfOrAdmin(userId);
        return Result.success(memoryService.listRecentMemories(
                "sess-" + userId, limit));
    }

    /**
     * 手动触发主动提醒扫描。
     * POST /agent/memory/reminder/scan
     * <p>
     * 提醒正文是 {@code ProactiveReminderService#remindForUser} 里写死的示例数字，
     * 且扫描会把生成的提醒推到 IM。定时任务侧已有 mock 档门禁（见
     * {@code DailyReportScheduler#isMockProfile}），本入口必须同规则，
     * 否则一次 curl 就能让假告警进入真实通知渠道。
     */
    @PostMapping("/reminder/scan")
    @RequireRole({"ADMIN"})
    public Result<List<String>> scanReminders() {
        if (!isMockProfile()) {
            log.warn("主动提醒扫描已拒绝：当前 profile {} 非 mock，而提醒正文为写死示例"
                            + "（SKU B08X4-001、可售 4 天、环比降 28% 等，与 shopId 无关），"
                            + "且会推送到 IM。恢复该能力需改为真实数据查询。",
                    Arrays.toString(environment.getActiveProfiles()));
            return Result.failure("主动提醒扫描仅在 mock 档可用：提醒内容目前为写死示例，"
                    + "非 mock 环境不产出伪造告警");
        }
        return Result.success(proactiveReminderService.scanAndRemind());
    }

    /**
     * 与 {@code DailyReportScheduler}/{@code CustomerServiceImpl} 同规则：
     * 模拟内容只允许在 mock 档产出，未显式声明即按非 mock 处理。
     */
    private boolean isMockProfile() {
        for (String p : environment.getActiveProfiles()) {
            if ("mock".equals(p)) {
                return true;
            }
        }
        return false;
    }

    private long currentUserId() {
        Integer userId = UserContext.getUserId();
        if (userId == null || userId <= 0) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "未登录");
        }
        return userId.longValue();
    }

    private void requireSelfOrAdmin(Long targetUserId) {
        long currentUserId = currentUserId();
        if (targetUserId == null || targetUserId <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "userId 非法");
        }
        if (targetUserId == currentUserId || "ADMIN".equalsIgnoreCase(UserContext.getRole())) {
            return;
        }
        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无权访问其他用户数据");
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
