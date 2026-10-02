package com.amz.service.impl;

import com.amz.context.UserContext;
import com.amz.exception.CodeErrorException;
import com.amz.mapper.AdAutoRuleMapper;
import com.amz.mapper.AdSearchTermMapper;
import com.amz.model.AdAutoRule;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@DisplayName("广告自动规则多租户隔离测试")
class AdAutoRuleServiceImplTest {

    private AdAutoRuleServiceImpl service;
    private AdAutoRuleMapper ruleMapper;
    private AdSearchTermMapper searchTermMapper;

    @BeforeEach
    void setUp() {
        service = new AdAutoRuleServiceImpl();
        ruleMapper = mock(AdAutoRuleMapper.class);
        searchTermMapper = mock(AdSearchTermMapper.class);
        ReflectionTestUtils.setField(service, "adAutoRuleMapper", ruleMapper);
        ReflectionTestUtils.setField(service, "adSearchTermMapper", searchTermMapper);
    }

    @AfterEach
    void clearUserContext() {
        UserContext.clear();
    }

    @Test
    @DisplayName("创建规则时拒绝请求体中的他店 shopId")
    void createRuleRejectsForeignShopInBody() {
        authenticateForShop(1L);
        AdAutoRule rule = new AdAutoRule();
        rule.setShopId(2L);
        rule.setRuleName("foreign");
        rule.setRuleType("PERFORMANCE");

        assertThrows(CodeErrorException.class, () -> service.createRule(rule));
        verifyNoInteractions(ruleMapper);
    }

    @Test
    @DisplayName("更新他店规则时拒绝且不写库")
    void updateRuleRejectsForeignRule() {
        authenticateForShop(1L);
        when(ruleMapper.selectById(9L)).thenReturn(rule(9L, 2L));
        AdAutoRule update = rule(9L, 2L);
        update.setRuleName("hijacked");

        assertThrows(CodeErrorException.class, () -> service.updateRule(update));
        verify(ruleMapper, never()).updateById(any(AdAutoRule.class));
    }

    @Test
    @DisplayName("更新规则时禁止把 shopId 改到其他店铺")
    void updateRuleRejectsShopIdMutation() {
        authenticateForShop(1L);
        when(ruleMapper.selectById(9L)).thenReturn(rule(9L, 1L));
        AdAutoRule update = rule(9L, 2L);
        update.setRuleName("changed");

        CodeErrorException error = assertThrows(CodeErrorException.class,
                () -> service.updateRule(update));
        assertEquals("规则所属店铺不可修改", error.getMessage());
        verify(ruleMapper, never()).updateById(any(AdAutoRule.class));
    }

    @Test
    @DisplayName("启停他店规则时拒绝且不更新")
    void toggleRuleRejectsForeignRule() {
        authenticateForShop(1L);
        when(ruleMapper.selectById(9L)).thenReturn(rule(9L, 2L));

        assertThrows(CodeErrorException.class, () -> service.toggleRule(9L, false));
        verify(ruleMapper, never()).updateById(any(AdAutoRule.class));
    }

    @Test
    @DisplayName("删除他店规则时拒绝且不删除")
    void deleteRuleRejectsForeignRule() {
        authenticateForShop(1L);
        when(ruleMapper.selectById(9L)).thenReturn(rule(9L, 2L));

        assertThrows(CodeErrorException.class, () -> service.deleteRule(9L));
        verify(ruleMapper, never()).deleteById(9L);
    }

    @Test
    @DisplayName("执行他店单条规则时拒绝且不读取报表")
    void executeRuleRejectsForeignRule() {
        authenticateForShop(1L);
        when(ruleMapper.selectById(9L)).thenReturn(rule(9L, 2L));

        assertThrows(CodeErrorException.class, () -> service.executeRule(9L));
        verifyNoInteractions(searchTermMapper);
        verify(ruleMapper, never()).updateById(any(AdAutoRule.class));
    }

    @Test
    @DisplayName("批量执行他店规则时拒绝且不扫描规则")
    void executeRulesRejectsForeignShop() {
        authenticateForShop(1L);

        assertThrows(CodeErrorException.class, () -> service.executeRules(2L));
        verifyNoInteractions(ruleMapper);
    }

    @Test
    @DisplayName("创建规则在无认证上下文时必须 fail-closed")
    void createRuleWithoutAuthenticatedContextFailsClosed() {
        AdAutoRule rule = rule(null, 1L);
        rule.setRuleName("anonymous");

        assertThrows(CodeErrorException.class, () -> service.createRule(rule));
        verifyNoInteractions(ruleMapper);
    }
    private static void authenticateForShop(Long shopId) {
        UserContext.setUserId(7);
        UserContext.setRole("OPERATOR");
        UserContext.setShops(List.of(shopId));
    }

    private static AdAutoRule rule(Long id, Long shopId) {
        AdAutoRule rule = new AdAutoRule();
        rule.setId(id);
        rule.setShopId(shopId);
        rule.setRuleName("rule-" + id);
        rule.setRuleType("PERFORMANCE");
        rule.setEnabled(1);
        return rule;
    }
}