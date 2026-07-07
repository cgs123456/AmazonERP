package com.amz.service.impl;

import com.amz.mapper.ConversationMemoryMapper;
import com.amz.mapper.UserPreferenceMapper;
import com.amz.model.ConversationMemory;
import com.amz.model.LanguageEnum;
import com.amz.model.UserPreference;
import com.amz.service.MemoryService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
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
            pref.setPreferredShopId(1L);
            pref.setLanguage(LanguageEnum.ZH.name());
            pref.setLastActiveTime(LocalDateTime.now());
            pref.setCreateTime(LocalDateTime.now());
            pref.setUpdateTime(LocalDateTime.now());
            userPreferenceMapper.insert(pref);
            log.info("初始化用户偏好：userId={}", userId);
        }
        return pref;
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
                if (!shopId.equals(pref.getPreferredShopId())) {
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
        LambdaQueryWrapper<ConversationMemory> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ConversationMemory::getSessionId, sessionId)
               .orderByDesc(ConversationMemory::getId)
               .last("LIMIT " + Math.max(1, limit));
        // 时间正序返回（ oldest first 便于拼装到 messages）
        List<ConversationMemory> list = conversationMemoryMapper.selectList(wrapper);
        java.util.Collections.reverse(list);
        return list;
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
