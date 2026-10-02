package com.amz.service.impl;

import com.amz.client.AdvertisingApiClient;
import com.amz.context.UserContext;
import com.amz.exception.InvalidParamException;
import com.amz.mapper.AdAutoRuleMapper;
import com.amz.mapper.AdSearchTermMapper;
import com.amz.model.AdAutoRule;
import com.amz.model.AdSearchTerm;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 广告自动规则的执行语义契约。
 *
 * <p>本类的三条硬约束，都是代码里看不出来、但运营按字面意思理解就会出事的地方：
 * <ol>
 *   <li>没有任何调度器调用 {@code executeRules}，也没有客户端把动作下发到广告账号，
 *       所以「执行」只能产出建议，返回值不许出现「已执行」这类字段；</li>
 *   <li>{@code scope}/{@code scope_value} 必须真正参与扫描过滤——此前它们只落库不生效，
 *       一条「限定某活动」的规则实际会命中全店搜索词；</li>
 *   <li>非法枚举、缺上界的 BETWEEN 在写入时就要拒绝。放过去的话判定的恒为 false，
 *       规则永远不命中，而页面上看起来是一条已启用的规则。</li>
 * </ol>
 */
@DisplayName("广告自动规则执行语义契约")
class AdAutoRuleSemanticsTest {

    private AdAutoRuleServiceImpl service;
    private AdAutoRuleMapper ruleMapper;
    private AdSearchTermMapper searchTermMapper;

    @BeforeAll
    static void initMybatisTableInfo() {
        // 断言 wrapper 生成的 SQL 前必须先把实体注册进 MyBatis-Plus 的 lambda 缓存。
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), AdSearchTerm.class);
    }

    @BeforeEach
    void setUp() {
        service = new AdAutoRuleServiceImpl();
        ruleMapper = mock(AdAutoRuleMapper.class);
        searchTermMapper = mock(AdSearchTermMapper.class);
        ReflectionTestUtils.setField(service, "adAutoRuleMapper", ruleMapper);
        ReflectionTestUtils.setField(service, "adSearchTermMapper", searchTermMapper);
        UserContext.setUserId(7);
        UserContext.setRole("OPERATOR");
        UserContext.setShops(List.of(1L));
    }

    @AfterEach
    void clearUserContext() {
        UserContext.clear();
    }

    // ==================== 写入校验 ====================

    @Test
    @DisplayName("合法的种子规则写法必须能通过校验")
    void createAcceptsWellFormedRule() {
        when(ruleMapper.insert(any(AdAutoRule.class))).thenReturn(1);

        AdAutoRule saved = service.createRule(validRule());

        assertEquals(Integer.valueOf(1), saved.getEnabled());
        assertEquals(Integer.valueOf(7), saved.getTimeWindow());
        verify(ruleMapper).insert(any(AdAutoRule.class));
    }

    @Test
    @DisplayName("条件字段不在 DDL 枚举内时拒绝写入")
    void createRejectsUnknownConditionField() {
        AdAutoRule rule = validRule();
        rule.setConditionField("PROFIT");

        assertThrows(InvalidParamException.class, () -> service.createRule(rule));
    }

    @Test
    @DisplayName("比较符不在 DDL 枚举内时拒绝写入")
    void createRejectsUnknownConditionOp() {
        AdAutoRule rule = validRule();
        rule.setConditionOp("ABOVE");

        assertThrows(InvalidParamException.class, () -> service.createRule(rule));
    }

    @Test
    @DisplayName("动作不在执行分支内时拒绝写入：ENABLE 只有 DDL 注释里有，代码没有分支")
    void createRejectsActionWithoutExecutionBranch() {
        AdAutoRule rule = validRule();
        rule.setAction("ENABLE");

        assertThrows(InvalidParamException.class, () -> service.createRule(rule));
    }

    @Test
    @DisplayName("BETWEEN 缺上界时拒绝写入：否则判定恒为 false，规则永远不命中")
    void createRejectsBetweenWithoutUpperBound() {
        AdAutoRule rule = validRule();
        rule.setConditionOp("BETWEEN");
        rule.setConditionValue2(null);

        assertThrows(InvalidParamException.class, () -> service.createRule(rule));
    }

    @Test
    @DisplayName("BETWEEN 上界小于下界时拒绝写入：同样是永远不命中的死规则")
    void createRejectsInvertedBetweenBounds() {
        AdAutoRule rule = validRule();
        rule.setConditionOp("BETWEEN");
        rule.setConditionValue(new BigDecimal("50"));
        rule.setConditionValue2(new BigDecimal("10"));

        assertThrows(InvalidParamException.class, () -> service.createRule(rule));
    }

    @Test
    @DisplayName("统计窗口超出 1-365 天时拒绝写入：负数会让起始日期跑到未来")
    void createRejectsNonPositiveTimeWindow() {
        AdAutoRule rule = validRule();
        rule.setTimeWindow(0);

        assertThrows(InvalidParamException.class, () -> service.createRule(rule));
    }

    @Test
    @DisplayName("ASIN 作用范围被拒绝：搜索词报表表没有 ASIN 字段，写了也判不出来")
    void createRejectsAsinScope() {
        AdAutoRule rule = validRule();
        rule.setScope("ASIN");
        rule.setScopeValue("B0ABC12345");

        assertThrows(InvalidParamException.class, () -> service.createRule(rule));
    }

    @Test
    @DisplayName("限定活动但不填活动 ID 时拒绝写入：静默退化成全店规则")
    void createRejectsCampaignScopeWithoutValue() {
        AdAutoRule rule = validRule();
        rule.setScope("CAMPAIGN");
        rule.setScopeValue("  ");

        assertThrows(InvalidParamException.class, () -> service.createRule(rule));
    }

    @Test
    @DisplayName("限定关键词但范围值不是数字时拒绝写入：keyword_id 是 BIGINT")
    void createRejectsKeywordScopeWithNonNumericValue() {
        AdAutoRule rule = validRule();
        rule.setScope("KEYWORD");
        rule.setScopeValue("跑步步带");

        assertThrows(InvalidParamException.class, () -> service.createRule(rule));
    }

    @Test
    @DisplayName("改判比较方式时按合并后的有效规则校验：只 PUT 一个字段也要看到 BETWEEN 缺上界")
    void updateValidatesMergedView() {
        AdAutoRule existing = validRule();
        existing.setId(9L);
        existing.setConditionValue2(null);
        when(ruleMapper.selectById(9L)).thenReturn(existing);

        AdAutoRule patch = new AdAutoRule();
        patch.setId(9L);
        patch.setConditionOp("BETWEEN");

        assertThrows(InvalidParamException.class, () -> service.updateRule(patch));
        verify(ruleMapper, org.mockito.Mockito.never()).updateById(any(AdAutoRule.class));
    }

    @Test
    @DisplayName("只改规则名的 PUT 不被新增校验挡住：未提交的字段按库里现值判定")
    void updateAcceptsPartialRename() {
        AdAutoRule existing = validRule();
        existing.setId(9L);
        when(ruleMapper.selectById(9L)).thenReturn(existing);
        when(ruleMapper.updateById(any(AdAutoRule.class))).thenReturn(1);

        AdAutoRule patch = new AdAutoRule();
        patch.setId(9L);
        patch.setRuleName("改名了");

        assertEquals("改名了", service.updateRule(patch).getRuleName());
    }

    // ==================== 扫描范围 ====================

    @Test
    @DisplayName("限定活动的规则只扫描该活动的搜索词")
    @SuppressWarnings({"unchecked", "rawtypes"})
    void executeScansOnlyConfiguredCampaign() {
        AdAutoRule rule = validRule();
        rule.setId(9L);
        rule.setScope("CAMPAIGN");
        rule.setScopeValue("camp-777");
        stubExecution(rule);

        service.executeRule(9L);

        LambdaQueryWrapper<AdSearchTerm> wrapper = capturedSearchTermWrapper();
        assertTrue(wrapper.getCustomSqlSegment().contains("campaign_id ="),
                "活动范围没有进 SQL：" + wrapper.getCustomSqlSegment());
        assertTrue(wrapper.getParamNameValuePairs().containsValue("camp-777"),
                "活动 ID 没有作为绑定参数传入：" + wrapper.getParamNameValuePairs());
    }

    @Test
    @DisplayName("限定关键词的规则按 keyword_id 过滤")
    @SuppressWarnings({"unchecked", "rawtypes"})
    void executeScansOnlyConfiguredKeyword() {
        AdAutoRule rule = validRule();
        rule.setId(9L);
        rule.setScope("KEYWORD");
        rule.setScopeValue("4242");
        stubExecution(rule);

        service.executeRule(9L);

        LambdaQueryWrapper<AdSearchTerm> wrapper = capturedSearchTermWrapper();
        assertTrue(wrapper.getCustomSqlSegment().contains("keyword_id ="),
                "关键词范围没有进 SQL：" + wrapper.getCustomSqlSegment());
        assertTrue(wrapper.getParamNameValuePairs().containsValue(4242L),
                "关键词 ID 没有按 BIGINT 传入：" + wrapper.getParamNameValuePairs());
    }

    @Test
    @DisplayName("没有范围值的种子规则保持全店扫描，行为不变")
    @SuppressWarnings({"unchecked", "rawtypes"})
    void executeWithoutScopeScansWholeShop() {
        AdAutoRule rule = validRule();
        rule.setId(9L);
        rule.setScope("KEYWORD");
        rule.setScopeValue(null);
        stubExecution(rule);

        service.executeRule(9L);

        String sql = capturedSearchTermWrapper().getCustomSqlSegment();
        assertFalse(sql.contains("keyword_id"), "空范围值却加了关键词过滤：" + sql);
        assertFalse(sql.contains("campaign_id"), "空范围值却加了活动过滤：" + sql);
    }

    // ==================== 结果诚实性 ====================

    @Test
    @DisplayName("执行结果声明未下发到广告账号，且每条动作只给建议")
    void executeResultIsExplicitlyAdviceOnly() {
        AdAutoRule rule = validRule();
        rule.setId(9L);
        rule.setAction("PAUSE");
        stubExecution(rule, searchTerm("yoga mat", new BigDecimal("80"), BigDecimal.ONE));

        Map<String, Object> result = service.executeRule(9L);

        assertEquals(Boolean.FALSE, result.get("appliedToAdAccount"));
        assertEquals(1, result.get("actionCount"));
        List<Map<String, Object>> actions = matchedActions(result);
        assertEquals(Boolean.FALSE, actions.get(0).get("applied"));
        assertTrue(String.valueOf(actions.get(0).get("suggestion")).contains("暂停"));
        assertFalse(actions.get(0).containsKey("executed"),
                "返回值不能再出现 executed 字段：广告账号没有被改过");
    }

    @Test
    @DisplayName("加价动作把比例写进建议文案，而不是留一个看起来像执行结果的字段")
    void increaseBidSuggestionCarriesPercentage() {
        AdAutoRule rule = validRule();
        rule.setId(9L);
        rule.setAction("INCREASE_BID");
        rule.setActionValue(new BigDecimal("30"));
        stubExecution(rule, searchTerm("running shoes", new BigDecimal("1"), new BigDecimal("50")));

        Map<String, Object> result = service.executeRule(9L);
        List<Map<String, Object>> actions = matchedActions(result);

        assertEquals("加价 30%", actions.get(0).get("suggestion"));
    }

    @Test
    @DisplayName("规则引擎不得持有广告客户端：现在没有执行器，接上就必须同步改语义")
    void ruleEngineHoldsNoAdvertisingClient() {
        assertTrue(Arrays.stream(AdAutoRuleServiceImpl.class.getDeclaredFields())
                .noneMatch(field -> AdvertisingApiClient.class.isAssignableFrom(field.getType())),
                "AdAutoRuleServiceImpl 又持有了 AdvertisingApiClient：请确认动作是否真的下发，"
                        + "否则 appliedToAdAccount 必须是 false");
    }

    // ==================== 夹具 ====================

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> matchedActions(Map<String, Object> result) {
        return (List<Map<String, Object>>) result.get("matchedActions");
    }

    private void stubExecution(AdAutoRule rule, AdSearchTerm... terms) {
        when(ruleMapper.selectById(9L)).thenReturn(rule);
        when(searchTermMapper.selectList(any())).thenReturn(List.of(terms));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private LambdaQueryWrapper<AdSearchTerm> capturedSearchTermWrapper() {
        ArgumentCaptor<LambdaQueryWrapper> captor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(searchTermMapper).selectList(captor.capture());
        return captor.getValue();
    }

    private static AdSearchTerm searchTerm(String term, BigDecimal acos, BigDecimal sales) {
        AdSearchTerm value = new AdSearchTerm();
        value.setId(1L);
        value.setShopId(1L);
        value.setCampaignId("camp-777");
        value.setSearchTerm(term);
        value.setReportDate(LocalDate.now().minusDays(1));
        value.setCost(new BigDecimal("40"));
        value.setSales(sales);
        value.setAcos(acos);
        value.setImpressions(1000L);
        value.setClicks(10L);
        value.setOrders(1);
        return value;
    }

    /** 与 V1 迁移里的种子规则同形：ACOS > 50 则暂停。 */
    private static AdAutoRule validRule() {
        AdAutoRule rule = new AdAutoRule();
        rule.setShopId(1L);
        rule.setRuleName("高ACoS自动暂停");
        rule.setRuleType("KEYWORD_PAUSE");
        rule.setConditionField("ACOS");
        rule.setConditionOp("GT");
        rule.setConditionValue(new BigDecimal("50"));
        rule.setAction("PAUSE");
        rule.setEnabled(1);
        return rule;
    }
}
