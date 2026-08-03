package com.amz.service;

import com.amz.model.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Listing 健康监控服务。
 */
public interface ListingMonitorService {

    // ==================== Listing 健康度 ====================
    ListingHealth saveHealthCheck(ListingHealth health);
    ListingHealth checkListing(Long shopId, String asin, String title, String bullets, String description,
                               Integer imageCount, String searchTerms, String status);
    List<ListingHealth> listHealth(Long shopId, String severity);
    Map<String, Object> healthSummary(Long shopId);

    // ==================== 变更日志 ====================
    void logChange(Long shopId, String asin, String sku, String fieldName, String oldValue, String newValue, String source, String operator);
    List<ListingChangeLog> listChangeLogs(Long shopId, String asin, String fieldName);

    // ==================== 关键词排名 ====================
    KeywordRanking saveRanking(KeywordRanking ranking);
    List<KeywordRanking> listRankings(Long shopId, String asin, String keyword);
    Map<String, Object> rankingTrend(Long shopId, String asin, String keyword, Integer days);

    // ==================== 竞品监控 ====================
    CompetitorMonitor saveCompetitor(CompetitorMonitor monitor);
    List<CompetitorMonitor> listCompetitors(Long shopId, String competitorAsin);
    Map<String, Object> competitorComparison(Long shopId, String myAsin, String competitorAsin, Integer days);

    // ==================== Buy Box ====================
    BuyBox saveBuyBox(BuyBox buyBox);
    List<BuyBox> listBuyBox(Long shopId, String asin);
    Map<String, Object> buyBoxSummary(Long shopId);
}
