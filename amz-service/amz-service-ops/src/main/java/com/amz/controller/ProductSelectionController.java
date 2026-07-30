package com.amz.controller;

import com.amz.annotation.ShopScoped;
import com.amz.model.KeywordResearch;
import com.amz.result.Result;
import com.amz.service.ProductSelectionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

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
    @PostMapping("/ai-suggestion/{opportunityId}")
    public Result aiSuggestion(@PathVariable Long opportunityId) {
        return productSelectionService.aiSuggestion(opportunityId);
    }
}
