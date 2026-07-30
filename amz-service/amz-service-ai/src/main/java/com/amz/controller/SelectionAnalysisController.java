package com.amz.controller;

import com.amz.agent.selection.SelectionAnalysisResult;
import com.amz.agent.selection.SelectionAnalysisService;
import com.amz.agent.selection.SelectionOpportunityInput;
import com.amz.result.Result;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

/**
 * 选品分析 REST 接口。
 * <p>
 * 接收 ops 服务通过 Feign 透传的选品机会数据，调用 DeepSeek 生成 AI 选品建议。
 */
@RestController
@RequestMapping("/ai/selection")
public class SelectionAnalysisController {

    @Autowired
    private SelectionAnalysisService selectionAnalysisService;

    /**
     * 分析选品机会，生成 AI 建议。
     * POST /ai/selection/analyze
     * Body: { asin, title, category, marketplace, avgPrice, avgReviews, avgRating,
     *         searchVolume, competitorCount, reviewBarrier, opportunityScore,
     *         trend30d, trend90d }
     *
     * @param body 选品机会数据
     * @return { aiSummary, aiSuggestion }
     */
    @PostMapping("/analyze")
    public Result<Map<String, Object>> analyze(@RequestBody Map<String, Object> body) {
        if (body == null || body.isEmpty()) {
            return Result.failure("选品数据不能为空");
        }

        SelectionOpportunityInput input = mapToInput(body);
        SelectionAnalysisResult result = selectionAnalysisService.analyzeOpportunity(input);

        Map<String, Object> data = new HashMap<>();
        data.put("aiSummary", result.getAiSummary());
        data.put("aiSuggestion", result.getAiSuggestion());
        return Result.success(data);
    }

    private SelectionOpportunityInput mapToInput(Map<String, Object> body) {
        SelectionOpportunityInput input = new SelectionOpportunityInput();
        input.setAsin(asStr(body.get("asin")));
        input.setTitle(asStr(body.get("title")));
        input.setCategory(asStr(body.get("category")));
        input.setMarketplace(asStr(body.get("marketplace")));
        input.setAvgPrice(asBigDecimal(body.get("avgPrice")));
        input.setAvgReviews(asInt(body.get("avgReviews")));
        input.setAvgRating(asBigDecimal(body.get("avgRating")));
        input.setSearchVolume(asInt(body.get("searchVolume")));
        input.setCompetitorCount(asInt(body.get("competitorCount")));
        input.setReviewBarrier(asStr(body.get("reviewBarrier")));
        input.setOpportunityScore(asBigDecimal(body.get("opportunityScore")));
        input.setTrend30d(asStr(body.get("trend30d")));
        input.setTrend90d(asStr(body.get("trend90d")));
        return input;
    }

    private String asStr(Object obj) {
        return obj == null ? null : obj.toString();
    }

    private Integer asInt(Object obj) {
        if (obj == null) return null;
        if (obj instanceof Number) return ((Number) obj).intValue();
        try { return Integer.valueOf(obj.toString()); } catch (Exception e) { return null; }
    }

    private BigDecimal asBigDecimal(Object obj) {
        if (obj == null) return null;
        if (obj instanceof BigDecimal) return (BigDecimal) obj;
        if (obj instanceof Number) return BigDecimal.valueOf(((Number) obj).doubleValue());
        try { return new BigDecimal(obj.toString()); } catch (Exception e) { return null; }
    }
}
