package com.amz.service;

import com.amz.model.AdReport;
import com.amz.model.BidSchedule;
import com.amz.optimizer.KeywordOptimizer;

import java.util.List;

/**
 * 广告管理服务接口。
 */
public interface AdService {

    /**
     * 查询店铺广告报表（活动级），含 ACoS/ROAS 等派生指标。
     */
    List<AdReport> getShopReports(Long shopId);

    /**
     * 查询店铺整体汇总指标。
     */
    AdReport getShopSummary(Long shopId);

    /**
     * 生成关键词优化建议。
     */
    List<KeywordOptimizer.Suggestion> optimizeKeywords(Long shopId, String campaignId);

    /**
     * 创建分时调价规则。
     */
    BidSchedule createBidSchedule(BidSchedule schedule);

    /**
     * 查询店铺的分时调价规则。
     */
    List<BidSchedule> listBidSchedules(Long shopId);
}
