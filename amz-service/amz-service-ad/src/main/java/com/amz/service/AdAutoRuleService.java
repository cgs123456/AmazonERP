package com.amz.service;

import com.amz.model.AdAutoRule;
import com.amz.result.PageRequest;
import com.amz.result.PageResult;

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
    PageResult<AdAutoRule> listRules(Long shopId, String ruleType, PageRequest page);

    /** 启用/禁用规则 */
    boolean toggleRule(Long ruleId, boolean enabled);

    /** 删除规则 */
    boolean deleteRule(Long ruleId);

    /**
     * 扫描该店铺所有启用规则，逐条产出建议。
     * <p>
     * 没有调度器调用它，也不会把动作下发到广告账号；结果里的
     * {@code appliedToAdAccount} 恒为 false。
     */
    Map<String, Object> executeRules(Long shopId);

    /**
     * 按单条规则的口径扫描搜索词报表，返回命中的建议动作列表（未执行）。
     */
    Map<String, Object> executeRule(Long ruleId);
}
