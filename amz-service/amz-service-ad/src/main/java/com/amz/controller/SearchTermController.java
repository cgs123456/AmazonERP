package com.amz.controller;

import com.amz.annotation.ShopScoped;
import com.amz.model.AdAutoRule;
import com.amz.model.AdSearchTerm;
import com.amz.model.AdAsinKeyword;
import com.amz.model.ConvertingTerm;
import com.amz.result.Result;
import com.amz.service.AdAutoRuleService;
import com.amz.service.SearchTermService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 搜索词分析 & 广告自动规则 REST 端点。
 */
@RestController
@RequestMapping("/ad/search-term")
public class SearchTermController {

    @Autowired
    private SearchTermService searchTermService;

    @Autowired
    private AdAutoRuleService adAutoRuleService;

    // ==================== 搜索词报表 ====================

    /** 保存搜索词报表数据 */
    @ShopScoped
    @PostMapping
    public Result<AdSearchTerm> saveSearchTerm(@RequestBody AdSearchTerm searchTerm) {
        return Result.success(searchTermService.saveSearchTerm(searchTerm));
    }

    /** 查询搜索词列表 */
    @ShopScoped
    @GetMapping("/list/{shopId}")
    public Result<List<AdSearchTerm>> listSearchTerms(@PathVariable Long shopId,
                                                       @RequestParam(required = false) String campaignId,
                                                       @RequestParam(required = false) String searchTerm) {
        return Result.success(searchTermService.listSearchTerms(shopId, campaignId, searchTerm));
    }

    /** 搜索词综合分析 */
    @ShopScoped
    @GetMapping("/analyze/{shopId}")
    public Result<Map<String, Object>> analyzeSearchTerms(@PathVariable Long shopId,
                                                           @RequestParam(required = false) String campaignId,
                                                           @RequestParam(defaultValue = "7") Integer days) {
        return Result.success(searchTermService.analyzeSearchTerms(shopId, campaignId, days));
    }

    /** 搜索词聚类分析 */
    @ShopScoped
    @GetMapping("/cluster/{shopId}")
    public Result<Map<String, Object>> clusterSearchTerms(@PathVariable Long shopId,
                                                           @RequestParam(required = false) String campaignId,
                                                           @RequestParam(defaultValue = "7") Integer days) {
        return Result.success(searchTermService.clusterSearchTerms(shopId, campaignId, days));
    }

    // ==================== 出单词库 ====================

    /** 自动提取出单词 */
    @ShopScoped
    @PostMapping("/converting/extract/{shopId}")
    public Result<List<ConvertingTerm>> extractConvertingTerms(@PathVariable Long shopId,
                                                                @RequestParam(defaultValue = "7") Integer days) {
        return Result.success(searchTermService.extractConvertingTerms(shopId, days));
    }

    /** 查询出单词库 */
    @ShopScoped
    @GetMapping("/converting/list/{shopId}")
    public Result<List<ConvertingTerm>> listConvertingTerms(@PathVariable Long shopId,
                                                             @RequestParam(required = false) String asin) {
        return Result.success(searchTermService.listConvertingTerms(shopId, asin));
    }

    // ==================== ASIN 关键词反查 ====================

    /** ASIN 关键词反查 */
    @ShopScoped
    @GetMapping("/asin-reverse/{shopId}")
    public Result<List<AdAsinKeyword>> reverseLookupAsin(@PathVariable Long shopId,
                                                          @RequestParam String asin) {
        return Result.success(searchTermService.reverseLookupAsin(shopId, asin));
    }

    /** 批量保存 ASIN 关键词反查数据 */
    @ShopScoped
    @PostMapping("/asin-reverse/batch")
    public Result<List<AdAsinKeyword>> saveAsinKeywords(@RequestBody List<AdAsinKeyword> keywords) {
        return Result.success(searchTermService.saveAsinKeywords(keywords));
    }

    // ==================== 广告自动规则 ====================

    /** 创建自动规则 */
    @ShopScoped
    @PostMapping("/rule")
    public Result<AdAutoRule> createRule(@RequestBody AdAutoRule rule) {
        return Result.success(adAutoRuleService.createRule(rule));
    }

    /** 更新规则 */
    @ShopScoped
    @PutMapping("/rule/{id}")
    public Result<AdAutoRule> updateRule(@PathVariable Long id, @RequestBody AdAutoRule rule) {
        rule.setId(id);
        return Result.success(adAutoRuleService.updateRule(rule));
    }

    /** 查询规则列表 */
    @ShopScoped
    @GetMapping("/rule/list/{shopId}")
    public Result<List<AdAutoRule>> listRules(@PathVariable Long shopId,
                                               @RequestParam(required = false) String ruleType) {
        return Result.success(adAutoRuleService.listRules(shopId, ruleType));
    }

    /** 启用/禁用规则 */
    @ShopScoped
    @PostMapping("/rule/{id}/toggle")
    public Result<Boolean> toggleRule(@PathVariable Long id, @RequestParam boolean enabled) {
        return Result.success(adAutoRuleService.toggleRule(id, enabled));
    }

    /** 删除规则 */
    @ShopScoped
    @DeleteMapping("/rule/{id}")
    public Result<Boolean> deleteRule(@PathVariable Long id) {
        return Result.success(adAutoRuleService.deleteRule(id));
    }

    /** 执行所有启用的规则 */
    @ShopScoped
    @PostMapping("/rule/execute/{shopId}")
    public Result<Map<String, Object>> executeRules(@PathVariable Long shopId) {
        return Result.success(adAutoRuleService.executeRules(shopId));
    }

    /** 执行单个规则 */
    @ShopScoped
    @PostMapping("/rule/{ruleId}/execute")
    public Result<Map<String, Object>> executeRule(@PathVariable Long ruleId) {
        return Result.success(adAutoRuleService.executeRule(ruleId));
    }
}
