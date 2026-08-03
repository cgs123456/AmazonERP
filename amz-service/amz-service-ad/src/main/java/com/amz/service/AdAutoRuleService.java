package com.amz.service;

import com.amz.model.AdAutoRule;
import com.amz.optimizer.KeywordOptimizer;

import java.util.List;
import java.util.Map;

/**
 * 广告自动规则服务接口。
 */
public interface AdAutoRuleService {

    /** 创建自动规则 */
    AdAutoRule createRule(AdAutoRule rule);

    /** 更新规则 */
    AdAutoRule updateRule(AdAutoRule rule);

    /** 查询规则列表 */
    List<AdAutoRule> listRules(Long shopId, String ruleType);

    /** 启用/禁用规则 */
    boolean toggleRule(Long ruleId, boolean enabled);

    /** 删除规则 */
    boolean deleteRule(Long ruleId);

    /**
     * 执行自动规则：扫描所有启用规则，匹配条件后执行动作。
     * @return 执行结果摘要
     */
    Map<String, Object> executeRules(Long shopId);

    /**
     * 执行单个规则。
     * @return 匹配的关键词及建议动作列表
     */
    Map<String, Object> executeRule(Long ruleId);
}
