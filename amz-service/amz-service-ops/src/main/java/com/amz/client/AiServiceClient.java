package com.amz.client;

import com.amz.result.Result;
import com.amz.client.fallback.AiServiceClientFallbackFactory;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.Map;

/**
 * AI 微服务 Feign 客户端。
 * 用于调用 amz-service-ai 的选品分析、评论分析等 AI 能力。
 */
@FeignClient(name = "amz-service-ai", contextId = "aiServiceClient", fallbackFactory = AiServiceClientFallbackFactory.class)
public interface AiServiceClient {

    /**
     * 调用 DeepSeek 生成选品建议。
     * Body 包含 asin/title/category/marketplace/avgPrice/avgReviews/avgRating/
     * searchVolume/competitorCount/reviewBarrier/opportunityScore/trend30d/trend90d 等字段。
     *
     * @param opportunity 选品机会数据（Map 形式避免跨模块类依赖）
     * @return { aiSummary, aiSuggestion }
     */
    @PostMapping("/ai/selection/analyze")
    Result<Map<String, Object>> analyzeSelection(@RequestBody Map<String, Object> opportunity);
}
