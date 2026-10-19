package com.amz.service.impl;

import com.amz.context.UserContext;
import com.amz.mapper.ConversationMemoryMapper;
import com.amz.mapper.UserPreferenceMapper;
import com.amz.model.UserPreference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Agent 偏好的店铺 ID 写入必须过租户校验。
 * <p>
 * <b>为什么需要这个测试</b>：{@code preferredShopId} 有两个写入点，都不校验归属——
 * <ol>
 *   <li>{@code getOrCreatePreference} 新建时硬编码 {@code 1L}；</li>
 *   <li>{@code extractAndUpdatePreference} 从用户消息正则解析后直接写入。</li>
 * </ol>
 * 而它的下游消费者会把该值当作兜底 shopId 注入 Agent 工具调用
 * （{@code MemoryAwareAgentService.injectDefaultShopIdIfNeeded}）与定时推送
 * （{@code DailyReportScheduler} / {@code ProactiveReminderService}）。
 * 于是用户说一句「店铺ID是999」就能把后续所有工具调用指向别家店铺。
 * <p>
 * <b>为什么不是安全洞</b>：{@code ErpToolExecutor} 的工具粒度 guard 会拦下未授权
 * shopId，所以读不到别家数据。但后果是 Agent 对用户报「无权访问该店铺数据」而用户
 * 不知道为什么，且坏值长期留在偏好表里反复触发——可用性缺陷，必须修。
 */
@DisplayName("Agent 偏好店铺 ID：必须属于当前用户授权店铺")
@ExtendWith(MockitoExtension.class)
class MemoryPreferredShopIdTenantTest {

    @Mock
    private UserPreferenceMapper userPreferenceMapper;

    @Mock
    private ConversationMemoryMapper conversationMemoryMapper;

    private MemoryServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new MemoryServiceImpl();
        org.springframework.test.util.ReflectionTestUtils.setField(service, "userPreferenceMapper", userPreferenceMapper);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "conversationMemoryMapper", conversationMemoryMapper);
    }

    @AfterEach
    void clearContext() {
        UserContext.clear();
    }

    @Test
    @DisplayName("提取到未授权店铺：不写入偏好，保留原值")
    void extractRejectsUnauthorizedShop() {
        UserContext.setUserId(7);
        UserContext.setShops(List.of(1L));
        UserPreference existing = preference(1L);
        when(userPreferenceMapper.selectOne(any())).thenReturn(existing);

        service.extractAndUpdatePreference(7L, "帮我看看店铺ID是999的数据");

        assertEquals(1L, existing.getPreferredShopId(),
                "未授权店铺不得写入偏好——下游会拿它当兜底 shopId 注入工具调用");
        verify(userPreferenceMapper, never()).updateById(any(UserPreference.class));
    }

    @Test
    @DisplayName("提取到已授权店铺：正常写入")
    void extractAcceptsAuthorizedShop() {
        UserContext.setUserId(7);
        UserContext.setShops(List.of(1L, 42L));
        UserPreference existing = preference(1L);
        when(userPreferenceMapper.selectOne(any())).thenReturn(existing);

        service.extractAndUpdatePreference(7L, "以后都看店铺ID是42");

        assertEquals(42L, existing.getPreferredShopId());
        verify(userPreferenceMapper).updateById(existing);
    }

    @Test
    @DisplayName("无任何身份时不猜店铺：拒绝写入而非放行")
    void extractWithoutIdentityDoesNotGrant() {
        UserPreference existing = preference(1L);
        when(userPreferenceMapper.selectOne(any())).thenReturn(existing);

        service.extractAndUpdatePreference(7L, "店铺ID是999");

        assertEquals(1L, existing.getPreferredShopId(),
                "无身份时 isShopAllowed 返回 false（§S33.121），不得解释为可写任意店铺");
        verify(userPreferenceMapper, never()).updateById(any(UserPreference.class));
    }

    @Test
    @DisplayName("新建偏好不硬编码店铺 1：该店可能不属于当前用户")
    void newPreferenceDoesNotAssumeShopOne() {
        UserContext.setUserId(7);
        UserContext.setShops(List.of(42L));
        when(userPreferenceMapper.selectOne(any())).thenReturn(null);

        service.getOrCreatePreference(7L);

        ArgumentCaptor<UserPreference> captor = ArgumentCaptor.forClass(UserPreference.class);
        verify(userPreferenceMapper).insert(captor.capture());
        UserPreference created = captor.getValue();
        // 有授权店铺时取第一家；一家都没有时留空，让下游显式要求选择，
        // 而不是默默指向一个用户可能无权访问的店铺。
        assertEquals(42L, created.getPreferredShopId(),
                "新建偏好应取当前用户授权店铺，而不是硬编码 1L");
    }

    @Test
    @DisplayName("一家授权店铺都没有时：不留兜底店铺")
    void newPreferenceWithoutShopsLeavesShopNull() {
        UserContext.setUserId(7);
        when(userPreferenceMapper.selectOne(any())).thenReturn(null);

        service.getOrCreatePreference(7L);

        ArgumentCaptor<UserPreference> captor = ArgumentCaptor.forClass(UserPreference.class);
        verify(userPreferenceMapper).insert(captor.capture());
        assertNull(captor.getValue().getPreferredShopId(),
                "无授权店铺时不得猜一个——留空让上游显式要求选择店铺");
    }

    private static UserPreference preference(Long shopId) {
        UserPreference pref = new UserPreference();
        pref.setId(1L);
        pref.setUserId(7L);
        pref.setPreferredShopId(shopId);
        pref.setLanguage("ZH");
        return pref;
    }
}
