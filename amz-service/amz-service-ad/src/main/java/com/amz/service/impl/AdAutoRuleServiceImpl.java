package com.amz.service.impl;

import com.amz.client.AdvertisingApiClient;
import com.amz.exception.AttrIsNullException;
import com.amz.mapper.AdAutoRuleMapper;
import com.amz.mapper.AdKeywordMapper;
import com.amz.mapper.AdSearchTermMapper;
import com.amz.model.*;
import com.amz.service.AdAutoRuleService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 广告自动规则服务实现。
 * <p>
 * 定时扫描启用规则 → 按条件匹配关键词/活动 → 执行动作（调价/暂停/否词）。
 */
@Slf4j
@Service
public class AdAutoRuleServiceImpl implements AdAutoRuleService {

    @Autowired
    private AdAutoRuleMapper adAutoRuleMapper;

    @Autowired
    private AdKeywordMapper adKeywordMapper;

    @Autowired
    private AdSearchTermMapper adSearchTermMapper;

    @Autowired
    private AdvertisingApiClient advertisingApiClient;

    @Override
    public AdAutoRule createRule(AdAutoRule rule) {
        if (rule.getShopId() == null || rule.getRuleName() == null || rule.getRuleType() == null) {
            throw new AttrIsNullException("店铺ID、规则名称和规则类型不能为空");
        }
        if (rule.getEnabled() == null) rule.setEnabled(1);
        if (rule.getPriority() == null) rule.setPriority(0);
        if (rule.getTimeWindow() == null) rule.setTimeWindow(7);
        adAutoRuleMapper.insert(rule);
        log.info("广告自动规则已创建：ruleName={}, type={}", rule.getRuleName(), rule.getRuleType());
        return rule;
    }

    @Override
    public AdAutoRule updateRule(AdAutoRule rule) {
        if (rule.getId() == null) {
            throw new AttrIsNullException("规则ID不能为空");
        }
        adAutoRuleMapper.updateById(rule);
        return rule;
    }

    @Override
    public List<AdAutoRule> listRules(Long shopId, String ruleType) {
        LambdaQueryWrapper<AdAutoRule> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(AdAutoRule::getShopId, shopId);
        if (ruleType != null && !ruleType.isBlank()) {
            wrapper.eq(AdAutoRule::getRuleType, ruleType);
        }
        wrapper.orderByDesc(AdAutoRule::getPriority);
        return adAutoRuleMapper.selectList(wrapper);
    }

    @Override
    public boolean toggleRule(Long ruleId, boolean enabled) {
        AdAutoRule rule = adAutoRuleMapper.selectById(ruleId);
        if (rule == null) {
            throw new AttrIsNullException("规则不存在：id=" + ruleId);
        }
        rule.setEnabled(enabled ? 1 : 0);
        adAutoRuleMapper.updateById(rule);
        return true;
    }

    @Override
    public boolean deleteRule(Long ruleId) {
        adAutoRuleMapper.deleteById(ruleId);
        return true;
    }

    @Override
    public Map<String, Object> executeRules(Long shopId) {
        LambdaQueryWrapper<AdAutoRule> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(AdAutoRule::getShopId, shopId)
               .eq(AdAutoRule::getEnabled, 1)
               .orderByDesc(AdAutoRule::getPriority);
        List<AdAutoRule> rules = adAutoRuleMapper.selectList(wrapper);

        List<Map<String, Object>> ruleResults = new ArrayList<>();
        int totalActions = 0;

        for (AdAutoRule rule : rules) {
            try {
                Map<String, Object> result = executeRule(rule.getId());
                int actions = (int) result.getOrDefault("actionCount", 0);
                totalActions += actions;
                ruleResults.add(result);
            } catch (Exception e) {
                log.error("规则执行异常：ruleId={}, ruleName={}", rule.getId(), rule.getRuleName(), e);
                Map<String, Object> errResult = new LinkedHashMap<>();
                errResult.put("ruleId", rule.getId());
                errResult.put("ruleName", rule.getRuleName());
                errResult.put("error", e.getMessage());
                ruleResults.add(errResult);
            }
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("shopId", shopId);
        summary.put("rulesExecuted", rules.size());
        summary.put("totalActions", totalActions);
        summary.put("ruleResults", ruleResults);
        log.info("广告自动规则批量执行完成：shopId={}, rules={}, actions={}", shopId, rules.size(), totalActions);
        return summary;
    }

    @Override
    public Map<String, Object> executeRule(Long ruleId) {
        AdAutoRule rule = adAutoRuleMapper.selectById(ruleId);
        if (rule == null) {
            throw new AttrIsNullException("规则不存在：id=" + ruleId);
        }
        if (rule.getEnabled() == null || rule.getEnabled() != 1) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("ruleId", ruleId);
            result.put("ruleName", rule.getRuleName());
            result.put("skipped", "规则未启用");
            result.put("actionCount", 0);
            return result;
        }

        // 获取时间窗口内的搜索词数据
        LocalDate startDate = LocalDate.now().minusDays(rule.getTimeWindow());
        LambdaQueryWrapper<AdSearchTerm> stWrapper = new LambdaQueryWrapper<>();
        stWrapper.eq(AdSearchTerm::getShopId, rule.getShopId())
                 .ge(AdSearchTerm::getReportDate, startDate);
        List<AdSearchTerm> searchTerms = adSearchTermMapper.selectList(stWrapper);

        // 按 searchTerm 聚合
        Map<String, List<AdSearchTerm>> grouped = searchTerms.stream()
                .collect(Collectors.groupingBy(AdSearchTerm::getSearchTerm));

        List<Map<String, Object>> matchedActions = new ArrayList<>();

        for (Map.Entry<String, List<AdSearchTerm>> entry : grouped.entrySet()) {
            String term = entry.getKey();
            List<AdSearchTerm> records = entry.getValue();

            // 聚合指标
            BigDecimal totalCost = records.stream().map(AdSearchTerm::getCost).filter(Objects::nonNull)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal totalSales = records.stream().map(AdSearchTerm::getSales).filter(Objects::nonNull)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            long totalClicks = records.stream().mapToLong(t -> t.getClicks() != null ? t.getClicks() : 0).sum();
            long totalImpressions = records.stream().mapToLong(t -> t.getImpressions() != null ? t.getImpressions() : 0).sum();
            int totalOrders = records.stream().mapToInt(t -> t.getOrders() != null ? t.getOrders() : 0).sum();

            BigDecimal acos = totalSales.compareTo(BigDecimal.ZERO) > 0
                    ? totalCost.multiply(new BigDecimal("100")).divide(totalSales, 2, RoundingMode.HALF_UP)
                    : BigDecimal.ZERO;
            BigDecimal cr = totalClicks > 0
                    ? BigDecimal.valueOf(totalOrders).multiply(new BigDecimal("100")).divide(BigDecimal.valueOf(totalClicks), 2, RoundingMode.HALF_UP)
                    : BigDecimal.ZERO;
            BigDecimal ctr = totalImpressions > 0
                    ? BigDecimal.valueOf(totalClicks).multiply(new BigDecimal("100")).divide(BigDecimal.valueOf(totalImpressions), 2, RoundingMode.HALF_UP)
                    : BigDecimal.ZERO;
            BigDecimal cpc = totalClicks > 0
                    ? totalCost.divide(BigDecimal.valueOf(totalClicks), 2, RoundingMode.HALF_UP)
                    : BigDecimal.ZERO;

            // 获取条件字段值
            BigDecimal conditionValue = getConditionValue(rule.getConditionField(), acos, cr, ctr, cpc, totalCost, totalSales, totalImpressions);

            // 匹配条件
            if (matchesCondition(conditionValue, rule.getConditionOp(), rule.getConditionValue(), rule.getConditionValue2())) {
                Map<String, Object> action = new LinkedHashMap<>();
                action.put("searchTerm", term);
                action.put("matchedValue", conditionValue);
                action.put("action", rule.getAction());

                // 执行动作
                switch (rule.getAction()) {
                    case "PAUSE":
                        action.put("executed", "暂停关键词/搜索词投放");
                        break;
                    case "INCREASE_BID":
                        action.put("suggestedBid", rule.getActionValue() != null
                                ? "加价 " + rule.getActionValue() + "%" : "加价 25%");
                        break;
                    case "DECREASE_BID":
                        action.put("suggestedBid", rule.getActionValue() != null
                                ? "降价 " + rule.getActionValue() + "%" : "降价 20%");
                        break;
                    case "ADD_NEGATIVE":
                        action.put("executed", "加入否定关键词");
                        break;
                    case "INCREASE_BUDGET":
                        action.put("executed", "增加活动预算");
                        break;
                    case "DECREASE_BUDGET":
                        action.put("executed", "减少活动预算");
                        break;
                    default:
                        action.put("executed", "未知动作");
                }
                matchedActions.add(action);
            }
        }

        // 更新规则最后执行时间
        rule.setLastExecuted(LocalDateTime.now());
        adAutoRuleMapper.updateById(rule);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ruleId", rule.getId());
        result.put("ruleName", rule.getRuleName());
        result.put("ruleType", rule.getRuleType());
        result.put("actionCount", matchedActions.size());
        result.put("matchedActions", matchedActions);
        return result;
    }

    private BigDecimal getConditionValue(String field, BigDecimal acos, BigDecimal cr, BigDecimal ctr,
                                          BigDecimal cpc, BigDecimal spend, BigDecimal sales, long impressions) {
        switch (field) {
            case "ACOS": return acos;
            case "CR": return cr;
            case "CTR": return ctr;
            case "CPC": return cpc;
            case "SPEND": return spend;
            case "SALES": return sales;
            case "IMPRESSIONS": return BigDecimal.valueOf(impressions);
            default: return BigDecimal.ZERO;
        }
    }

    private boolean matchesCondition(BigDecimal value, String op, BigDecimal threshold, BigDecimal threshold2) {
        if (value == null || op == null || threshold == null) return false;
        switch (op) {
            case "GT": return value.compareTo(threshold) > 0;
            case "GTE": return value.compareTo(threshold) >= 0;
            case "LT": return value.compareTo(threshold) < 0;
            case "LTE": return value.compareTo(threshold) <= 0;
            case "EQ": return value.compareTo(threshold) == 0;
            case "BETWEEN": return threshold2 != null
                    && value.compareTo(threshold) >= 0
                    && value.compareTo(threshold2) <= 0;
            default: return false;
        }
    }
}
