package com.amz.service.impl;

import com.amz.client.AiServiceClient;
import com.amz.mapper.KeywordResearchMapper;
import com.amz.mapper.SelectionOpportunityMapper;
import com.amz.model.KeywordResearch;
import com.amz.model.SelectionOpportunity;
import com.amz.result.Result;
import com.amz.service.ProductSelectionService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 选品服务实现。
 * <p>
 * 基于 Helium 10 / Jungle Scout 式的 8 维度分析：
 * 1. 市场容量（搜索量 × 平均价格）
 * 2. 竞争程度（竞品数 + review_barrier）
 * 3. 机会评分（公式：searchVolume / (competitorCount * avgReviews^0.5) × 100，标准化到 0-100）
 * 4. 价格区间分析
 * 5. 评论壁垒（avgReviews < 50 = LOW, 50-200 = MEDIUM, > 200 = HIGH）
 * 6. 趋势分析（30天/90天）
 * 7. 季节性检测
 * 8. AI 建议（通过 Feign 调用 amz-service-ai → DeepSeek）
 * <p>
 * 由于无真实数据源（未接入 Helium 10 / SP-API Brand Analytics），
 * 使用基于品类基准的合理模拟数据。
 */
@Slf4j
@Service
public class ProductSelectionServiceImpl implements ProductSelectionService {

    @Autowired
    private SelectionOpportunityMapper opportunityMapper;

    @Autowired
    private KeywordResearchMapper keywordResearchMapper;

    @Autowired(required = false)
    private AiServiceClient aiServiceClient;

    @Override
    public Result analyzeMarket(String keyword, String marketplace) {
        if (keyword == null || keyword.isBlank()) {
            return Result.failure("关键词不能为空");
        }
        if (marketplace == null || marketplace.isBlank()) {
            marketplace = "US";
        }

        log.info("开始分析关键词市场：keyword={} marketplace={}", keyword, marketplace);

        // ===== 1. 模拟生成该关键词下 Top N 的机会商品 =====
        // 关键词 hash 作为种子，保证同一关键词分析结果稳定可复现
        long seed = keyword.hashCode() + marketplace.hashCode();
        ThreadLocalRandom rand = ThreadLocalRandom.current();
        rand.setSeed(seed);

        // 默认店铺 ID 为 1（无登录上下文时的占位，前端可带 shopId 参数覆盖）
        Long shopId = 1L;
        String category = inferCategory(keyword);

        // 模拟市场基准值（基于品类）
        int baseSearchVolume = 5000 + rand.nextInt(0, 50000);
        int baseCompetitorCount = 50 + rand.nextInt(0, 500);
        BigDecimal baseAvgPrice = BigDecimal.valueOf(15 + rand.nextDouble(0, 60))
                .setScale(2, RoundingMode.HALF_UP);
        int baseAvgReviews = rand.nextInt(20, 400);
        BigDecimal baseAvgRating = BigDecimal.valueOf(3.5 + rand.nextDouble(0, 1.4))
                .setScale(1, RoundingMode.HALF_UP);

        List<SelectionOpportunity> opportunities = new ArrayList<>();
        int topN = 5;
        for (int i = 0; i < topN; i++) {
            SelectionOpportunity opp = buildOpportunity(
                    shopId, keyword, category, marketplace,
                    baseSearchVolume, baseCompetitorCount,
                    baseAvgPrice, baseAvgReviews, baseAvgRating,
                    i);
            opportunities.add(opp);
            opportunityMapper.insert(opp);
        }

        // 关键词调研结果落库
        KeywordResearch research = buildKeywordResearch(shopId, keyword, marketplace,
                baseSearchVolume, baseCompetitorCount);
        keywordResearchMapper.insert(research);

        Map<String, Object> summary = new HashMap<>();
        summary.put("keyword", keyword);
        summary.put("marketplace", marketplace);
        summary.put("category", category);
        summary.put("marketSize", baseAvgPrice.multiply(BigDecimal.valueOf(baseSearchVolume)));
        summary.put("avgPrice", baseAvgPrice);
        summary.put("avgReviews", baseAvgReviews);
        summary.put("avgRating", baseAvgRating);
        summary.put("searchVolume", baseSearchVolume);
        summary.put("competitorCount", baseCompetitorCount);
        summary.put("reviewBarrier", calcReviewBarrier(baseAvgReviews));
        summary.put("trend30d", randomTrend(rand));
        summary.put("trend90d", randomTrend(rand));
        summary.put("seasonality", detectSeasonality(keyword));
        summary.put("opportunities", opportunities);
        summary.put("keywordResearch", research);

        log.info("关键词 {} 分析完成，生成 {} 个机会商品", keyword, opportunities.size());
        return Result.success(summary);
    }

    @Override
    public Result findOpportunities(Long shopId, String category, String sortBy, int limit) {
        if (shopId == null) {
            return Result.failure("店铺 ID 不能为空");
        }
        if (limit <= 0 || limit > 100) {
            limit = 20;
        }
        if (sortBy == null || sortBy.isBlank()) {
            sortBy = "score";
        }

        LambdaQueryWrapper<SelectionOpportunity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(SelectionOpportunity::getShopId, shopId);
        if (category != null && !category.isBlank()) {
            wrapper.eq(SelectionOpportunity::getCategory, category);
        }
        switch (sortBy) {
            case "volume" -> wrapper.orderByDesc(SelectionOpportunity::getSearchVolume);
            case "competition" -> wrapper.orderByAsc(SelectionOpportunity::getCompetitorCount);
            default -> wrapper.orderByDesc(SelectionOpportunity::getOpportunityScore);
        }
        wrapper.last("LIMIT " + limit);
        List<SelectionOpportunity> list = opportunityMapper.selectList(wrapper);
        return Result.success(list);
    }

    @Override
    public Result analyzeCompetitors(String asin, String marketplace) {
        if (asin == null || asin.isBlank()) {
            return Result.failure("ASIN 不能为空");
        }
        if (marketplace == null || marketplace.isBlank()) {
            marketplace = "US";
        }

        log.info("竞品分析：asin={} marketplace={}", asin, marketplace);

        ThreadLocalRandom rand = ThreadLocalRandom.current();
        rand.setSeed((long) asin.hashCode() + marketplace.hashCode());

        List<Map<String, Object>> competitors = new ArrayList<>();
        int compCount = 5 + rand.nextInt(0, 8);
        for (int i = 0; i < compCount; i++) {
            Map<String, Object> comp = new HashMap<>();
            comp.put("asin", "B0" + (10000000L + rand.nextInt(0, 90000000)));
            comp.put("title", "Competitor Product " + (i + 1));
            comp.put("price", BigDecimal.valueOf(15 + rand.nextDouble(0, 60))
                    .setScale(2, RoundingMode.HALF_UP));
            comp.put("reviews", rand.nextInt(10, 500));
            comp.put("rating", BigDecimal.valueOf(3.5 + rand.nextDouble(0, 1.4))
                    .setScale(1, RoundingMode.HALF_UP));
            comp.put("bsr", rand.nextInt(1000, 50000));
            comp.put("sellers", rand.nextInt(1, 5));
            competitors.add(comp);
        }

        Map<String, Object> result = new HashMap<>();
        result.put("targetAsin", asin);
        result.put("marketplace", marketplace);
        result.put("competitorCount", compCount);
        result.put("competitors", competitors);
        result.put("differentiation", buildDifferentiationSuggestions(asin, competitors));

        return Result.success(result);
    }

    @Override
    public Result<KeywordResearch> researchKeyword(String keyword, String marketplace) {
        if (keyword == null || keyword.isBlank()) {
            return Result.failure("关键词不能为空");
        }
        if (marketplace == null || marketplace.isBlank()) {
            marketplace = "US";
        }

        log.info("关键词调研：keyword={} marketplace={}", keyword, marketplace);

        ThreadLocalRandom rand = ThreadLocalRandom.current();
        rand.setSeed((long) keyword.hashCode() + marketplace.hashCode());

        Long shopId = 1L;
        int searchVolume = 1000 + rand.nextInt(0, 80000);
        int competitorCount = 30 + rand.nextInt(0, 400);

        KeywordResearch research = buildKeywordResearch(shopId, keyword, marketplace, searchVolume, competitorCount);
        keywordResearchMapper.insert(research);

        return Result.success(research);
    }

    @Override
    public Result aiSuggestion(Long opportunityId) {
        if (opportunityId == null) {
            return Result.failure("机会 ID 不能为空");
        }

        SelectionOpportunity opp = opportunityMapper.selectById(opportunityId);
        if (opp == null) {
            return Result.failure("机会记录不存在: " + opportunityId);
        }

        if (aiServiceClient == null) {
            String fallback = "AI 服务未启用，无法生成 DeepSeek 选品建议。机会评分："
                    + opp.getOpportunityScore() + "，建议参考评分和评论壁垒综合决策。";
            opp.setAiSummary("AI 服务未启用");
            opp.setAiSuggestion(fallback);
            opp.setStatus("AI_SUGGESTED");
            opportunityMapper.updateById(opp);
            return Result.success(opp);
        }

        try {
            Map<String, Object> req = new HashMap<>();
            req.put("asin", opp.getAsin());
            req.put("title", opp.getTitle());
            req.put("category", opp.getCategory());
            req.put("marketplace", opp.getMarketplace());
            req.put("avgPrice", opp.getAvgPrice());
            req.put("avgReviews", opp.getAvgReviews());
            req.put("avgRating", opp.getAvgRating());
            req.put("searchVolume", opp.getSearchVolume());
            req.put("competitorCount", opp.getCompetitorCount());
            req.put("reviewBarrier", opp.getReviewBarrier());
            req.put("opportunityScore", opp.getOpportunityScore());
            req.put("trend30d", opp.getTrend30d());
            req.put("trend90d", opp.getTrend90d());

            Result<Map<String, Object>> resp = aiServiceClient.analyzeSelection(req);
            if (resp == null || resp.getCode() != 200 || resp.getData() == null) {
                String msg = resp != null ? resp.getMessage() : "AI 服务无响应";
                log.warn("AI 选品建议调用失败 opportunityId={} msg={}", opportunityId, msg);
                opp.setAiSummary("AI 分析失败");
                opp.setAiSuggestion("AI 服务调用失败: " + msg);
            } else {
                Map<String, Object> data = resp.getData();
                opp.setAiSummary((String) data.get("aiSummary"));
                opp.setAiSuggestion((String) data.get("aiSuggestion"));
                opp.setStatus("AI_SUGGESTED");
            }
            opportunityMapper.updateById(opp);
            return Result.success(opp);
        } catch (Exception e) {
            log.error("AI 选品建议调用异常 opportunityId={}", opportunityId, e);
            opp.setAiSummary("AI 调用异常");
            opp.setAiSuggestion("AI 服务调用异常: " + e.getMessage());
            opportunityMapper.updateById(opp);
            return Result.success(opp);
        }
    }

    // ===== 私有辅助方法 =====

    /**
     * 构造单条选品机会，计算 8 维度指标并落库。
     */
    private SelectionOpportunity buildOpportunity(
            Long shopId, String keyword, String category, String marketplace,
            int baseSearchVolume, int baseCompetitorCount,
            BigDecimal baseAvgPrice, int baseAvgReviews, BigDecimal baseAvgRating,
            int index) {
        ThreadLocalRandom rand = ThreadLocalRandom.current();

        SelectionOpportunity opp = new SelectionOpportunity();
        opp.setShopId(shopId);
        opp.setAsin("B0" + (10000000L + rand.nextInt(0, 90000000)));
        opp.setTitle(capitalize(keyword) + " - Product Variant " + (index + 1));
        opp.setCategory(category);
        opp.setMarketplace(marketplace);

        // 围绕基准值浮动 ±30%
        int searchVolume = Math.max(100, baseSearchVolume + rand.nextInt(-baseSearchVolume / 3, baseSearchVolume / 3));
        int competitorCount = Math.max(5, baseCompetitorCount + rand.nextInt(-baseCompetitorCount / 3, baseCompetitorCount / 3));
        BigDecimal avgPrice = baseAvgPrice.multiply(BigDecimal.valueOf(0.85 + rand.nextDouble(0, 0.3)))
                .setScale(2, RoundingMode.HALF_UP);
        int avgReviews = Math.max(5, baseAvgReviews + rand.nextInt(-baseAvgReviews / 3, baseAvgReviews / 3));
        BigDecimal avgRating = BigDecimal.valueOf(Math.max(3.0, baseAvgRating.doubleValue() + rand.nextDouble(-0.4, 0.4)))
                .setScale(1, RoundingMode.HALF_UP);

        opp.setAvgPrice(avgPrice);
        opp.setAvgReviews(avgReviews);
        opp.setAvgRating(avgRating);
        opp.setSearchVolume(searchVolume);
        opp.setCompetitorCount(competitorCount);
        opp.setReviewBarrier(calcReviewBarrier(avgReviews));
        opp.setOpportunityScore(calcOpportunityScore(searchVolume, competitorCount, avgReviews));
        opp.setTrend30d(randomTrend(rand));
        opp.setTrend90d(randomTrend(rand));
        opp.setStatus("ANALYZED");

        return opp;
    }

    /**
     * 机会评分公式：searchVolume / (competitorCount * avgReviews^0.5) × 100，标准化到 0-100。
     */
    private BigDecimal calcOpportunityScore(int searchVolume, int competitorCount, int avgReviews) {
        double denom = competitorCount * Math.sqrt(Math.max(1, avgReviews));
        double raw = denom > 0 ? (searchVolume * 100.0) / denom : 0;
        // 简单标准化：log 压缩后线性映射到 0-100
        double normalized = 100.0 * (1 - Math.exp(-raw / 200.0));
        normalized = Math.max(0, Math.min(100, normalized));
        return BigDecimal.valueOf(normalized).setScale(1, RoundingMode.HALF_UP);
    }

    /**
     * 评论壁垒分级：avgReviews < 50 = LOW, 50-200 = MEDIUM, > 200 = HIGH。
     */
    private String calcReviewBarrier(int avgReviews) {
        if (avgReviews < 50) return "LOW";
        if (avgReviews <= 200) return "MEDIUM";
        return "HIGH";
    }

    /**
     * 随机生成趋势方向。
     */
    private String randomTrend(ThreadLocalRandom rand) {
        int r = rand.nextInt(0, 100);
        if (r < 45) return "UP";
        if (r < 80) return "FLAT";
        return "DOWN";
    }

    /**
     * 关键词→品类推断（粗略规则）。
     */
    private String inferCategory(String keyword) {
        String k = keyword.toLowerCase();
        if (k.contains("earbud") || k.contains("headphone") || k.contains("speaker")) return "Electronics";
        if (k.contains("kitchen") || k.contains("cookware") || k.contains("bottle")) return "Home & Kitchen";
        if (k.contains("yoga") || k.contains("fitness") || k.contains("dumbbell")) return "Sports & Outdoors";
        if (k.contains("toy") || k.contains("game") || k.contains("puzzle")) return "Toys & Games";
        if (k.contains("cream") || k.contains("serum") || k.contains("makeup")) return "Beauty";
        if (k.contains("dog") || k.contains("cat") || k.contains("pet")) return "Pet Supplies";
        return "General";
    }

    /**
     * 季节性检测（基于关键词与月份的简单规则）。
     */
    private String detectSeasonality(String keyword) {
        String k = keyword.toLowerCase();
        if (k.contains("christmas") || k.contains("halloween") || k.contains("easter")
                || k.contains("valentine") || k.contains("holiday")) {
            return "STRONG_SEASONAL";
        }
        if (k.contains("summer") || k.contains("winter") || k.contains("swim") || k.contains("coat")) {
            return "MODERATE_SEASONAL";
        }
        return "NON_SEASONAL";
    }

    /**
     * 构造关键词调研记录。
     */
    private KeywordResearch buildKeywordResearch(Long shopId, String keyword, String marketplace,
                                                  int searchVolume, int competitorCount) {
        ThreadLocalRandom rand = ThreadLocalRandom.current();
        KeywordResearch research = new KeywordResearch();
        research.setShopId(shopId);
        research.setKeyword(keyword);
        research.setMarketplace(marketplace);
        research.setSearchVolume(searchVolume);
        research.setClickShare(BigDecimal.valueOf(rand.nextDouble(0.5, 25.0))
                .setScale(2, RoundingMode.HALF_UP));
        research.setConversionShare(BigDecimal.valueOf(rand.nextDouble(0.2, 15.0))
                .setScale(2, RoundingMode.HALF_UP));
        research.setTopAsin("B0" + (10000000L + rand.nextInt(0, 90000000)));
        research.setDifficultyScore(calcDifficultyScore(competitorCount, searchVolume));
        research.setRecommendedBid(BigDecimal.valueOf(0.5 + rand.nextDouble(0, 4.0))
                .setScale(2, RoundingMode.HALF_UP));
        return research;
    }

    /**
     * 竞争难度 0-100：竞品越多 / 搜索量越低，难度越高。
     */
    private BigDecimal calcDifficultyScore(int competitorCount, int searchVolume) {
        double ratio = competitorCount / (double) Math.max(1, searchVolume);
        double score = 100.0 * (1 - Math.exp(-ratio * 50.0));
        score = Math.max(0, Math.min(100, score));
        return BigDecimal.valueOf(score).setScale(1, RoundingMode.HALF_UP);
    }

    /**
     * 构造竞品差异化建议。
     */
    private List<String> buildDifferentiationSuggestions(String asin, List<Map<String, Object>> competitors) {
        List<String> suggestions = new ArrayList<>();
        suggestions.add("分析 Top 竞品评论痛点，针对差评关键词做差异化卖点");
        suggestions.add("定价避开竞品密集区间，参考 Buy Box 均价上下浮动 10-15%");
        suggestions.add("Listing 主图差异化（场景/对比/尺寸可视化）");
        suggestions.add("首批采购建议 200-500 件试销，根据 14 天转化数据加码");
        suggestions.add("搭配 coupon / vine 计划积累首批 30+ 评论突破壁垒");
        return suggestions;
    }

    private String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return s.substring(0, 1).toUpperCase() + s.substring(1);
    }
}
