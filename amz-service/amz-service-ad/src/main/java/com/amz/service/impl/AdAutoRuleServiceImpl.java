package com.amz.service.impl;

import com.amz.client.AdvertisingApiClient;
import com.amz.context.UserContext;
import com.amz.exception.AttrIsNullException;
import com.amz.exception.CodeErrorException;
import com.amz.exception.InvalidParamException;
import com.amz.mapper.AdAutoRuleMapper;
import com.amz.mapper.AdKeywordMapper;
import com.amz.mapper.AdSearchTermMapper;
import com.amz.model.AdAutoRule;
import com.amz.model.AdSearchTerm;
import com.amz.result.PageRequest;
import com.amz.result.PageResult;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
        if (rule == null || rule.getShopId() == null || rule.getRuleName() == null || rule.getRuleType() == null) {
            throw new AttrIsNullException("店铺ID、规则名称和规则类型不能为空");
        }
        requireShopAccess(rule.getShopId());
        if (rule.getEnabled() == null) rule.setEnabled(1);
        if (rule.getPriority() == null) rule.setPriority(0);
        if (rule.getTimeWindow() == null) rule.setTimeWindow(7);
        adAutoRuleMapper.insert(rule);
        log.info("广告自动规则已创建：ruleName={}, type={}", rule.getRuleName(), rule.getRuleType());
        return rule;
    }

    @Override
    public AdAutoRule updateRule(AdAutoRule rule) {
        if (rule == null || rule.getId() == null) {
            throw new AttrIsNullException("规则ID不能为空");
        }
        AdAutoRule existing = requireRuleAccess(rule.getId());
        if (rule.getShopId() != null && !Objects.equals(rule.getShopId(), existing.getShopId())) {
            throw new CodeErrorException("规则所属店铺不可修改");
        }
        rule.setShopId(existing.getShopId());
        adAutoRuleMapper.updateById(rule);
        return rule;
    }

    @Override
    public PageResult<AdAutoRule> listRules(Long shopId, String ruleType, PageRequest page) {
        PageRequest req = page == null ? PageRequest.first(PageRequest.DEFAULT_SIZE) : page;
        LambdaQueryWrapper<AdAutoRule> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(AdAutoRule::getShopId, shopId);
        if (ruleType != null && !ruleType.isBlank()) {
            wrapper.eq(AdAutoRule::getRuleType, ruleType);
        }
        CompositeCursor cursor = parseCompositeCursor(req, "priority");
        if (cursor != null) {
            wrapper.and(w -> w.lt(AdAutoRule::getPriority, cursor.key())
                    .or(inner -> inner.eq(AdAutoRule::getPriority, cursor.key())
                            .lt(AdAutoRule::getId, cursor.id())));
        }
        wrapper.orderByDesc(AdAutoRule::getPriority)
               .orderByDesc(AdAutoRule::getId)
               .last("LIMIT " + req.probeSize());
        List<AdAutoRule> rows = adAutoRuleMapper.selectList(wrapper);
        return PageResult.of(rows, req.size(),
                rule -> PageRequest.encodeCursor(rule.getPriority() + "|" + rule.getId()));
    }

    @Override
    public boolean toggleRule(Long ruleId, boolean enabled) {
        AdAutoRule rule = requireRuleAccess(ruleId);
        rule.setEnabled(enabled ? 1 : 0);
        adAutoRuleMapper.updateById(rule);
        return true;
    }

    @Override
    public boolean deleteRule(Long ruleId) {
        requireRuleAccess(ruleId);
        adAutoRuleMapper.deleteById(ruleId);
        return true;
    }

    @Override
    public Map<String, Object> executeRules(Long shopId) {
        requireShopAccess(shopId);
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
        AdAutoRule rule = requireRuleAccess(ruleId);
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

    private void requireShopAccess(Long shopId) {
        if (shopId == null) {
            throw new AttrIsNullException("店铺ID不能为空");
        }
        if (!UserContext.isShopAllowedStrict(shopId)) {
            log.warn("广告规则店铺越权访问拦截：userId={}, shopId={}", UserContext.getUserId(), shopId);
            throw new CodeErrorException("店铺不存在或无权访问");
        }
    }

    private static CompositeCursor parseCompositeCursor(PageRequest page, String field) {
        if (page == null || !page.hasCursor()) {
            return null;
        }
        String[] parts = page.payload().split("\\|", -1);
        if (parts.length != 2) {
            throw new InvalidParamException("分页游标非法：" + field + " 游标格式应为 值|id");
        }
        try {
            int key = Integer.parseInt(parts[0]);
            long id = Long.parseLong(parts[1]);
            if (id <= 0) {
                throw new InvalidParamException("分页游标非法：id 必须 > 0，实际 " + id);
            }
            return new CompositeCursor(key, id);
        } catch (NumberFormatException e) {
            throw new InvalidParamException("分页游标非法：" + field + " 或 id 不是整数");
        }
    }

    private record CompositeCursor(int key, long id) {
    }

    private AdAutoRule requireRuleAccess(Long ruleId) {
        if (ruleId == null) {
            throw new AttrIsNullException("规则ID不能为空");
        }
        AdAutoRule rule = adAutoRuleMapper.selectById(ruleId);
        if (rule == null || !UserContext.isShopAllowedStrict(rule.getShopId())) {
            log.warn("广告规则越权访问拦截：userId={}, ruleId={}", UserContext.getUserId(), ruleId);
            throw new CodeErrorException("规则不存在或无权访问");
        }
        return rule;
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
