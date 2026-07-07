package com.amz.service;

import com.amz.model.ConversationMemory;
import com.amz.model.UserPreference;

import java.util.List;

/**
 * Agent 记忆服务接口。
 * <p>
 * 提供用户偏好持久化、对话记忆管理、偏好自动提取等能力。
 */
public interface MemoryService {

    /**
     * 获取或初始化用户偏好（不存在则创建默认偏好）。
     */
    UserPreference getOrCreatePreference(Long userId);

    /**
     * 显式更新用户偏好。
     */
    UserPreference updatePreference(UserPreference preference);

    /**
     * 从用户输入中提取偏好信号并更新（如"我主要做电子产品"→preferredCategory=Electronics）。
     *
     * @param userId      用户 ID
     * @param userMessage 用户输入
     * @return 更新后的偏好
     */
    UserPreference extractAndUpdatePreference(Long userId, String userMessage);

    /**
     * 保存一条对话记忆。
     */
    void saveMemory(String sessionId, Long userId, String role, String content);

    /**
     * 查询指定会话最近 N 条对话记忆。
     */
    List<ConversationMemory> listRecentMemories(String sessionId, int limit);

    /**
     * 更新用户最后活跃时间。
     */
    void touchUserActive(Long userId);
}
