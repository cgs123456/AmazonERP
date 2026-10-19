package com.amz.service.impl;

import com.amz.context.UserContext;
import com.amz.exception.InvalidParamException;
import com.amz.mapper.ConversationMemoryMapper;
import com.amz.mapper.UserPreferenceMapper;
import com.amz.model.ConversationMemory;
import com.amz.model.LanguageEnum;
import com.amz.model.UserPreference;
import com.amz.result.PageRequest;
import com.amz.service.MemoryService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Agent 记忆服务实现。
 * <p>
 * 核心能力：
 * <ol>
 *   <li>用户偏好的初始化 / 显式更新 / 自动提取</li>
 *   <li>对话记忆按 sessionId 存储 + 最近 N 条查询</li>
 * </ol>
 * 偏好提取采用关键词规则匹配（离线实现，无需 LLM 二次调用）。
 */
@Slf4j
@Service
public class MemoryServiceImpl implements MemoryService {

    /** 对话记忆默认保留条数 */
    private static final int MEMORY_LIMIT = 10;

    /** 品类关键词正则：匹配"做XX产品/品类"或"主要做XX" */
    private static final Pattern CATEGORY_PATTERN = Pattern.compile(
            "(?:主要做|专注|专营|做)([\\u4e00-\\u9fa5A-Za-z]+?)(?:产品|品类|类目|的)");

    /** 店铺 ID 正则：匹配"店铺ID是1 / 店铺1 / shopId=1" */
    private static final Pattern SHOP_ID_PATTERN = Pattern.compile(
            "(?:店铺(?:ID|Id|id)?[是:=]?\\s*(\\d+))|(?:shop\\s*id\\s*[=:]?\\s*(\\d+))",
            Pattern.CASE_INSENSITIVE);

    /** 语言关键词：用户明确指定回复语言 */
    private static final Pattern LANG_PATTERN = Pattern.compile(
            "(用中文|用英文|用英语|用日文|用日语|用德文|用德语|in English|in Japanese|in German|auf Deutsch)");

    @Autowired
    private UserPreferenceMapper userPreferenceMapper;

    @Autowired
    private ConversationMemoryMapper conversationMemoryMapper;

    @Override
    public UserPreference getOrCreatePreference(Long userId) {
        LambdaQueryWrapper<UserPreference> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(UserPreference::getUserId, userId);
        UserPreference pref = userPreferenceMapper.selectOne(wrapper);
        if (pref == null) {
            pref = new UserPreference();
            pref.setUserId(userId);
            // 不硬编码 1L：店铺 1 可能不属于当前用户，写下去会让 Agent 的兜底 shopId
            // 指向一家无权访问的店（下游 injectDefaultShopIdIfNeeded 直接把它
            // 注入工具调用）。有授权店铺时取第一家；一家都没有时留空，
            // 让上游显式要求选择，而不是默默猜一个。
            pref.setPreferredShopId(defaultShopId());
            pref.setLanguage(LanguageEnum.ZH.name());
            pref.setLastActiveTime(LocalDateTime.now());
            pref.setCreateTime(LocalDateTime.now());
            pref.setUpdateTime(LocalDateTime.now());
            userPreferenceMapper.insert(pref);
            log.info("初始化用户偏好：userId={} preferredShopId={}", userId, pref.getPreferredShopId());
        }
        return pref;
    }

    /**
     * 当前用户的兜底店铺：授权列表的第一家；一家都没有时返回 {@code null}。
     * <p>
     * 取「第一家」而不是报错：偏好表只是兜底来源，用户随时可以在消息里指定别的店
     * （那条路径有 {@link #isPreferredShopAllowed} 校验）。留 {@code null} 时
     * 下游显式要求选择，比猜一个更安全。
     */
    private Long defaultShopId() {
        List<Long> shops = UserContext.getShops();
        return shops == null || shops.isEmpty() ? null : shops.get(0);
    }

    /**
     * 待写入的店铺 ID 是否属于当前用户。
     * <p>
     * 复用 {@code UserContext.isShopAllowed}：有用户身份时它是严格校验，
     * 无身份时拒绝（§S33.121）。也就是说定时任务/内部调用这条路径不会写偏好，
     * 而公网请求必须过授权——这正是我们要的。
     */
    private boolean isPreferredShopAllowed(Long shopId) {
        return shopId != null && UserContext.isShopAllowed(shopId);
    }

    @Override
    public UserPreference updatePreference(UserPreference preference) {
        preference.setUpdateTime(LocalDateTime.now());
        if (preference.getId() == null) {
            // 按 userId 查找
            UserPreference existing = getOrCreatePreference(preference.getUserId());
            preference.setId(existing.getId());
        }
        userPreferenceMapper.updateById(preference);
        return preference;
    }

    @Override
    public UserPreference extractAndUpdatePreference(Long userId, String userMessage) {
        if (userMessage == null || userMessage.isBlank()) {
            return getOrCreatePreference(userId);
        }
        UserPreference pref = getOrCreatePreference(userId);
        boolean changed = false;

        // 提取店铺 ID
        Matcher shopMatcher = SHOP_ID_PATTERN.matcher(userMessage);
        if (shopMatcher.find()) {
            String shopIdStr = shopMatcher.group(1) != null ? shopMatcher.group(1) : shopMatcher.group(2);
            try {
                Long shopId = Long.parseLong(shopIdStr);
                // 归属校验：preferredShopId 会被下游当兜底 shopId 注入 Agent 工具调用
                // （MemoryAwareAgentService）与定时推送（DailyReportScheduler /
                // ProactiveReminderService）。不校验的话，用户说一句「店铺ID是999」
                // 就能把后续所有调用指向别家店铺——ErpToolExecutor 的 guard 会拦下
                // 越权读取，但用户看到的是「无权访问该店铺数据」且不知道为什么，
                // 坏值还会长期留在偏好表里反复触发。
                if (!isPreferredShopAllowed(shopId)) {
                    log.warn("偏好店铺 ID 越权拦截：userId={} 请求店铺={} 已授权店铺={}",
                            userId, shopId, UserContext.getShops());
                } else if (!shopId.equals(pref.getPreferredShopId())) {
                    pref.setPreferredShopId(shopId);
                    changed = true;
                    log.info("用户偏好更新：userId={} preferredShopId={}", userId, shopId);
                }
            } catch (NumberFormatException ignore) {
                // 忽略解析失败
            }
        }

        // 提取品类
        Matcher catMatcher = CATEGORY_PATTERN.matcher(userMessage);
        if (catMatcher.find()) {
            String category = catMatcher.group(1).trim();
            if (!category.isEmpty() && !category.equals(pref.getPreferredCategory())) {
                pref.setPreferredCategory(category);
                changed = true;
                log.info("用户偏好更新：userId={} preferredCategory={}", userId, category);
            }
        }

        // 提取语言偏好
        Matcher langMatcher = LANG_PATTERN.matcher(userMessage);
        if (langMatcher.find()) {
            String langText = langMatcher.group(1).toLowerCase();
            LanguageEnum lang = resolveLangKeyword(langText);
            if (lang != null && !lang.name().equals(pref.getLanguage())) {
                pref.setLanguage(lang.name());
                changed = true;
                log.info("用户偏好更新：userId={} language={}", userId, lang.name());
            }
        }

        if (changed) {
            pref.setUpdateTime(LocalDateTime.now());
            userPreferenceMapper.updateById(pref);
        }
        return pref;
    }

    @Override
    public void saveMemory(String sessionId, Long userId, String role, String content) {
        ConversationMemory memory = new ConversationMemory();
        memory.setSessionId(sessionId);
        memory.setUserId(userId);
        memory.setRole(role);
        memory.setContent(content);
        memory.setCreateTime(LocalDateTime.now());
        conversationMemoryMapper.insert(memory);
    }

    @Override
    public List<ConversationMemory> listRecentMemories(String sessionId, int limit) {
        // limit 直接来自 HTTP（?limit=），不设上限就等于让调用方要求多宽的多宽地全表读；
        // 与 PageRequest 同口径：越界即参数错误，不静默收敛（静默会让调用方以为拿到了它要的条数）。
        if (limit < 1 || limit > PageRequest.MAX_SIZE) {
            throw new InvalidParamException("limit 必须在 1.." + PageRequest.MAX_SIZE
                    + " 之间，实际 " + limit);
        }
        LambdaQueryWrapper<ConversationMemory> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ConversationMemory::getSessionId, sessionId)
               .orderByDesc(ConversationMemory::getId)
               .last("LIMIT " + limit);
        // 时间正序返回（ oldest first 便于拼装到 messages）
        List<ConversationMemory> list = conversationMemoryMapper.selectList(wrapper);
        // 倒序复制而不是原地 reverse：不依赖 mapper 返回的是可变列表
        List<ConversationMemory> oldestFirst = new ArrayList<>(list.size());
        for (int i = list.size() - 1; i >= 0; i--) {
            oldestFirst.add(list.get(i));
        }
        return oldestFirst;
    }

    @Override
    public void touchUserActive(Long userId) {
        UserPreference pref = getOrCreatePreference(userId);
        pref.setLastActiveTime(LocalDateTime.now());
        pref.setUpdateTime(LocalDateTime.now());
        userPreferenceMapper.updateById(pref);
    }

    private LanguageEnum resolveLangKeyword(String text) {
        if (text.contains("中文")) return LanguageEnum.ZH;
        if (text.contains("英文") || text.contains("英语") || text.contains("english")) return LanguageEnum.EN;
        if (text.contains("日文") || text.contains("日语") || text.contains("japanese")) return LanguageEnum.JA;
        if (text.contains("德文") || text.contains("德语") || text.contains("german") || text.contains("deutsch")) return LanguageEnum.DE;
        return null;
    }
}
