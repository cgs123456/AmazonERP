package com.amz.service.impl;

import com.amz.context.UserContext;
import com.amz.exception.AttrIsNullException;
import com.amz.exception.CodeErrorException;
import com.amz.exception.InvalidParamException;
import com.amz.mapper.AdAutoRuleMapper;
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
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * 广告规则服务实现。
 * <p>
 * 按条件匹配搜索词报表 → 产出调价/暂停/否词<b>建议</b>。
 * <p>
 * 两条容易被字面意思误解的边界，写在这里以免下次「顺手补上」：
 * <ul>
 *   <li>本类不向广告账号下发任何动作。全仓只有 {@code BidScheduleExecutor}（分时调价）
 *       会真实改价，它走的是 {@code BidSchedule} 与 {@code AdvertisingApiClient}，与本类无关；
 *       因此执行结果固定带 {@code appliedToAdAccount=false}，动作明细只给 {@code suggestion}。</li>
 *   <li>没有任何调度器调用 {@code executeRules}，「自动规则」目前必须由页面或接口手动触发。</li>
 * </ul>
 * <p>
 * {@code rule_type} 仅作分类标签（列表筛选、展示），判定逻辑不看它，改判定要看
 * {@code condition_*} 与 {@code action}。
 */
@Slf4j
@Service
public class AdAutoRuleServiceImpl implements AdAutoRuleService {

    /** 取值来源：V1 迁移里 condition_field/condition_op/scope/action 的列注释。 */
    private static final Set<String> CONDITION_FIELDS =
            Set.of("ACOS", "CR", "CTR", "CPC", "SPEND", "SALES", "IMPRESSIONS");
    private static final Set<String> CONDITION_OPS = Set.of("GT", "GTE", "LT", "LTE", "EQ", "BETWEEN");
    private static final Set<String> SCOPES = Set.of("KEYWORD", "CAMPAIGN");

    /** 只登记有执行分支的动作：DDL 注释里的 ENABLE 至今没有分支，允许写入就是一条永不生效的规则。 */
    private static final Set<String> ACTIONS = Set.of(
            "PAUSE", "INCREASE_BID", "DECREASE_BID", "ADD_NEGATIVE", "INCREASE_BUDGET", "DECREASE_BUDGET");

    /** 与搜索词分析窗口上限保持一致，避免负数把起始日期推到未来。 */
    private static final int MAX_TIME_WINDOW_DAYS = 365;

    @Autowired
    private AdAutoRuleMapper adAutoRuleMapper;

    @Autowired
    private AdSearchTermMapper adSearchTermMapper;

    @Override
    public AdAutoRule createRule(AdAutoRule rule) {
        if (rule == null || rule.getShopId() == null || rule.getRuleName() == null || rule.getRuleType() == null) {
            throw new AttrIsNullException("店铺ID、规则名称和规则类型不能为空");
        }
        requireShopAccess(rule.getShopId());
        requireValidRule(rule);
        if (rule.getEnabled() == null) rule.setEnabled(1);
        if (rule.getPriority() == null) rule.setPriority(0);
        if (rule.getTimeWindow() == null) rule.setTimeWindow(7);
        adAutoRuleMapper.insert(rule);
        log.info("广告规则已创建：ruleName={}, type={}", rule.getRuleName(), rule.getRuleType());
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
        // PUT 可以只带被改的字段，所以校验对象是「库里现值 + 本次非空覆盖」的合并结果；
        // 只校验请求体会把合法的局部更新挡在门外，也会放过 BETWEEN 缺上界这类跨字段组合。
        requireValidRule(mergeForValidation(existing, rule));
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
            result.put("appliedToAdAccount", false);
            result.put("actionCount", 0);
            return result;
        }

        // 获取时间窗口内的搜索词数据
        int window = rule.getTimeWindow() == null ? 7 : rule.getTimeWindow();
        LocalDate startDate = LocalDate.now().minusDays(window);
        LambdaQueryWrapper<AdSearchTerm> stWrapper = new LambdaQueryWrapper<>();
        stWrapper.eq(AdSearchTerm::getShopId, rule.getShopId())
                 .ge(AdSearchTerm::getReportDate, startDate);
        applyScope(stWrapper, rule);
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

                // 给出建议：没有任何动作被下发到广告账号
                action.put("applied", false);
                switch (rule.getAction() == null ? "" : rule.getAction().trim()) {
                    case "PAUSE":
                        action.put("suggestion", "暂停该搜索词所在投放");
                        break;
                    case "INCREASE_BID":
                        action.put("suggestion", "加价 "
                                + (rule.getActionValue() != null ? rule.getActionValue().stripTrailingZeros().toPlainString() + "%" : "25%"));
                        break;
                    case "DECREASE_BID":
                        action.put("suggestion", "降价 "
                                + (rule.getActionValue() != null ? rule.getActionValue().stripTrailingZeros().toPlainString() + "%" : "20%"));
                        break;
                    case "ADD_NEGATIVE":
                        action.put("suggestion", "加入否定关键词");
                        break;
                    case "INCREASE_BUDGET":
                        action.put("suggestion", "提高活动预算");
                        break;
                    case "DECREASE_BUDGET":
                        action.put("suggestion", "降低活动预算");
                        break;
                    default:
                        action.put("suggestion", "未支持的动作，需人工判断");
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
        result.put("appliedToAdAccount", false);
        result.put("actionCount", matchedActions.size());
        result.put("matchedActions", matchedActions);
        result.put("note", "本接口只产出建议清单，未调用广告 API：暂停/否词/预算与改价都需要人工在广告后台执行。"
                + "全店唯一的真实改价通道是分时调价（BidSchedule，按小时自动下发）。");
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

    /**
     * 把 scope/scope_value 变成真正的扫描条件。
     * <p>
     * 缺值即视为全店：V1 种子里的三条规则都是 {@code scope=KEYWORD, scope_value=NULL}，
     * 加了过滤会让它们从「全店」静默变成「命中 0 条」。
     */
    private static void applyScope(LambdaQueryWrapper<AdSearchTerm> wrapper, AdAutoRule rule) {
        String scope = trimToNull(rule.getScope());
        String value = trimToNull(rule.getScopeValue());
        if (scope == null || value == null) {
            return;
        }
        switch (scope) {
            case "CAMPAIGN" -> wrapper.eq(AdSearchTerm::getCampaignId, value);
            case "KEYWORD" -> wrapper.eq(AdSearchTerm::getKeywordId, parseKeywordId(value));
            default -> throw new InvalidParamException("规则作用范围非法：" + scope);
        }
    }

    private static Long parseKeywordId(String value) {
        try {
            return Long.valueOf(value);
        } catch (NumberFormatException e) {
            throw new InvalidParamException("关键词作用范围必须是关键词 ID（数字），实际：" + value);
        }
    }

    /**
     * 规则能否被判定出来，取决于跨字段组合而不是单个字段，所以这些校验必须发生在写入前：
     * 放过去的就是页面上一条「已启用但永远不出结果」的规则。
     */
    private void requireValidRule(AdAutoRule rule) {
        if (trimToNull(rule.getRuleName()) == null) {
            throw new InvalidParamException("规则名称不能为空");
        }
        if (trimToNull(rule.getRuleType()) == null) {
            throw new InvalidParamException("规则类型不能为空");
        }
        requireIn(CONDITION_FIELDS, rule.getConditionField(), "条件字段");
        requireIn(CONDITION_OPS, rule.getConditionOp(), "比较方式");
        requireIn(ACTIONS, rule.getAction(), "动作");
        if (rule.getConditionValue() == null) {
            throw new InvalidParamException("阈值不能为空：判定会静默恒为 false");
        }
        if ("BETWEEN".equals(trimToNull(rule.getConditionOp()))) {
            if (rule.getConditionValue2() == null) {
                throw new InvalidParamException("BETWEEN 需要同时给出上界，否则规则永远不命中");
            }
            if (rule.getConditionValue2().compareTo(rule.getConditionValue()) < 0) {
                throw new InvalidParamException("BETWEEN 的上界不能小于下界");
            }
        }
        if (rule.getTimeWindow() != null
                && (rule.getTimeWindow() < 1 || rule.getTimeWindow() > MAX_TIME_WINDOW_DAYS)) {
            throw new InvalidParamException("统计窗口必须在 1-" + MAX_TIME_WINDOW_DAYS + " 天之间，实际 "
                    + rule.getTimeWindow());
        }
        String scope = trimToNull(rule.getScope());
        if (scope == null) {
            return;
        }
        if ("ASIN".equals(scope)) {
            throw new InvalidParamException(
                    "暂不支持按 ASIN 限定范围：搜索词报表表没有 ASIN 字段，写了也不会生效");
        }
        if (!SCOPES.contains(scope)) {
            throw new InvalidParamException("作用范围只能是 KEYWORD 或 CAMPAIGN，实际：" + scope);
        }
        String value = trimToNull(rule.getScopeValue());
        if (value == null) {
            throw new InvalidParamException("限定了作用范围就要给出对应 ID，否则规则会静默作用于全店");
        }
        if ("KEYWORD".equals(scope)) {
            parseKeywordId(value);
        }
    }

    /** 合并库里现值与本次非空覆盖，得到写库后真正生效的规则形态。 */
    private static AdAutoRule mergeForValidation(AdAutoRule existing, AdAutoRule patch) {
        AdAutoRule merged = new AdAutoRule();
        merged.setId(patch.getId() != null ? patch.getId() : existing.getId());
        merged.setShopId(existing.getShopId());
        merged.setRuleName(patch.getRuleName() != null ? patch.getRuleName() : existing.getRuleName());
        merged.setRuleType(patch.getRuleType() != null ? patch.getRuleType() : existing.getRuleType());
        merged.setScope(patch.getScope() != null ? patch.getScope() : existing.getScope());
        merged.setScopeValue(patch.getScopeValue() != null
                ? patch.getScopeValue() : existing.getScopeValue());
        merged.setConditionField(patch.getConditionField() != null
                ? patch.getConditionField() : existing.getConditionField());
        merged.setConditionOp(patch.getConditionOp() != null
                ? patch.getConditionOp() : existing.getConditionOp());
        merged.setConditionValue(patch.getConditionValue() != null
                ? patch.getConditionValue() : existing.getConditionValue());
        merged.setConditionValue2(patch.getConditionValue2() != null
                ? patch.getConditionValue2() : existing.getConditionValue2());
        merged.setAction(patch.getAction() != null ? patch.getAction() : existing.getAction());
        merged.setActionValue(patch.getActionValue() != null
                ? patch.getActionValue() : existing.getActionValue());
        merged.setTimeWindow(patch.getTimeWindow() != null
                ? patch.getTimeWindow() : existing.getTimeWindow());
        return merged;
    }

    private static void requireIn(Set<String> allowed, String value, String label) {
        String normalized = trimToNull(value);
        if (normalized == null || !allowed.contains(normalized.toUpperCase(Locale.ROOT))) {
            throw new InvalidParamException(label + "取值非法：" + value + "，可选 "
                    + String.join("/", new TreeSet<>(allowed)));
        }
    }

    private static String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private BigDecimal getConditionValue(String field, BigDecimal acos, BigDecimal cr, BigDecimal ctr,
                                         BigDecimal cpc, BigDecimal spend, BigDecimal sales, long impressions) {
        switch (field == null ? "" : field) {
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
