package com.amz.controller;

import com.amz.annotation.RequireRole;
import com.amz.annotation.ShopScoped;
import com.amz.model.KeywordResearch;
import com.amz.result.Result;
import com.amz.service.ProductSelectionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 选品分析 REST 端点：市场分析、蓝海机会、竞品分析、关键词调研、AI 选品建议。
 */
@RestController
@RequestMapping("/ops/selection")
public class ProductSelectionController {

    @Autowired
    private ProductSelectionService productSelectionService;

    /**
     * 分析关键词市场。
     * POST /ops/selection/market
     * Body: { "keyword": "wireless earbuds", "marketplace": "US" }
     */
    // §S29：选品分析三个端点都会消耗 LLM token（ai-suggestion 走 Feign 调
    // amz-service-ai），属「让系统花钱」的运营决策；且 analyzeHour / researchKeyword
    // 会把外部市场数据写进选品表。VIEWER 不应能触发。
    @RequireRole({"OPERATOR", "ADMIN"})
    @PostMapping("/market")
    public Result analyzeMarket(@RequestBody java.util.Map<String, String> body) {
        String keyword = body.get("keyword");
        String marketplace = body.get("marketplace");
        return productSelectionService.analyzeMarket(keyword, marketplace);
    }

    /**
     * 蓝海机会列表。
     * GET /ops/selection/opportunities?shopId=&category=&sortBy=&limit=
     */
    @ShopScoped
    @GetMapping("/opportunities")
    public Result findOpportunities(
            @RequestParam Long shopId,
            @RequestParam(required = false) String category,
            @RequestParam(required = false, defaultValue = "score") String sortBy,
            @RequestParam(required = false, defaultValue = "20") int limit) {
        return productSelectionService.findOpportunities(shopId, category, sortBy, limit);
    }

    /**
     * 竞品分析。
     * GET /ops/selection/competitors/{asin}?marketplace=
     */
    @GetMapping("/competitors/{asin}")
    public Result analyzeCompetitors(
            @PathVariable String asin,
            @RequestParam(required = false, defaultValue = "US") String marketplace) {
        return productSelectionService.analyzeCompetitors(asin, marketplace);
    }

    /**
     * 关键词调研。
     * POST /ops/selection/keyword
     * Body: { "keyword": "yoga mat", "marketplace": "US" }
     */
    // §S29：选品分析三个端点都会消耗 LLM token（ai-suggestion 走 Feign 调
    // amz-service-ai），属「让系统花钱」的运营决策；且 analyzeHour / researchKeyword
    // 会把外部市场数据写进选品表。VIEWER 不应能触发。
    @RequireRole({"OPERATOR", "ADMIN"})
    @PostMapping("/keyword")
    public Result<KeywordResearch> researchKeyword(@RequestBody java.util.Map<String, String> body) {
        String keyword = body.get("keyword");
        String marketplace = body.get("marketplace");
        return productSelectionService.researchKeyword(keyword, marketplace);
    }

    /**
     * AI 选品建议（基于已分析的机会记录）。
     * POST /ops/selection/ai-suggestion/{opportunityId}
     */
    // §S29：选品分析三个端点都会消耗 LLM token（ai-suggestion 走 Feign 调
    // amz-service-ai），属「让系统花钱」的运营决策；且 analyzeHour / researchKeyword
    // 会把外部市场数据写进选品表。VIEWER 不应能触发。
    @RequireRole({"OPERATOR", "ADMIN"})
    @PostMapping("/ai-suggestion/{opportunityId}")
    public Result aiSuggestion(@PathVariable Long opportunityId) {
        return productSelectionService.aiSuggestion(opportunityId);
    }
}
