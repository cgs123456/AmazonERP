package com.amz.controller;

import com.amz.agent.review.ReviewAnalysisResult;
import com.amz.agent.review.ReviewAnalysisService;
import com.amz.agent.review.ReviewInfo;
import com.amz.result.Result;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 评论分析 REST 接口。
 * <p>
 * 提供商品评论的 AI 分析能力，包括情感分析、痛点聚类和改进建议。
 */
@RestController
@RequestMapping("/ai/review")
public class ReviewAnalysisController {

    @Autowired
    private ReviewAnalysisService reviewAnalysisService;

    /**
     * 分析商品评论。
     * POST /ai/review/analyze
     * Body: [{ "rating": 1, "title": "...", "content": "...", "date": "2026-07-01", "verifiedPurchase": true }]
     *
     * @param reviews 评论列表
     * @return 分析结果（情感得分、痛点、建议、总结）
     */
    @PostMapping("/analyze")
    public Result<ReviewAnalysisResult> analyze(@RequestBody List<ReviewInfo> reviews) {
        if (reviews == null || reviews.isEmpty()) {
            return Result.failure("评论列表不能为空");
        }
        if (reviews.size() > 500) {
            return Result.failure("单次分析评论数不能超过 500 条");
        }
        ReviewAnalysisResult result = reviewAnalysisService.analyze(reviews);
        return Result.success(result);
    }
}
