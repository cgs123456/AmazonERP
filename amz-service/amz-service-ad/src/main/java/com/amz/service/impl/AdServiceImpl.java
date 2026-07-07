package com.amz.service.impl;

import com.amz.analytics.AdPerformanceAnalyzer;
import com.amz.client.AdvertisingApiClient;
import com.amz.mapper.AdKeywordMapper;
import com.amz.mapper.BidScheduleMapper;
import com.amz.model.AdKeyword;
import com.amz.model.AdReport;
import com.amz.model.BidSchedule;
import com.amz.optimizer.KeywordOptimizer;
import com.amz.service.AdService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 广告管理服务实现。
 */
@Service
public class AdServiceImpl implements AdService {

    @Autowired
    private AdPerformanceAnalyzer analyzer;

    @Autowired
    private AdKeywordMapper adKeywordMapper;

    @Autowired
    private BidScheduleMapper bidScheduleMapper;

    @Autowired
    private AdvertisingApiClient advertisingApiClient;

    @Autowired
    private KeywordOptimizer keywordOptimizer;

    @Override
    public List<AdReport> getShopReports(Long shopId) {
        // 模拟活动级报表（生产应从 amz_ad_report 表或 Advertising API Reports 拉取）
        List<AdReport> reports = new ArrayList<>();
        AdReport r1 = new AdReport();
        r1.setCampaignId("camp-001");
        r1.setImpressions(50000L);
        r1.setClicks(800L);
        r1.setCost(new BigDecimal("480.00"));
        r1.setSales(new BigDecimal("3200.00"));
        r1.setOrders(60);
        reports.add(r1);

        AdReport r2 = new AdReport();
        r2.setCampaignId("camp-002");
        r2.setImpressions(20000L);
        r2.setClicks(150L);
        r2.setCost(new BigDecimal("180.00"));
        r2.setSales(new BigDecimal("300.00"));
        r2.setOrders(10);
        reports.add(r2);

        analyzer.analyzeAll(reports);
        return reports;
    }

    @Override
    public AdReport getShopSummary(Long shopId) {
        return analyzer.summarize(getShopReports(shopId));
    }

    @Override
    public List<KeywordOptimizer.Suggestion> optimizeKeywords(Long shopId, String campaignId) {
        List<AdKeyword> keywords = advertisingApiClient.listKeywords(shopId, campaignId);
        // 关键词级报表（模拟）
        List<AdReport> reports = new ArrayList<>();
        for (AdKeyword kw : keywords) {
            AdReport r = new AdReport();
            r.setKeyword(kw.getKeyword());
            r.setImpressions(8000L);
            r.setClicks(120L);
            r.setCost(new BigDecimal("90.00"));
            r.setSales(new BigDecimal("600.00"));
            r.setOrders(12);
            reports.add(r);
        }
        return keywordOptimizer.optimize(keywords, reports);
    }

    @Override
    public BidSchedule createBidSchedule(BidSchedule schedule) {
        if (schedule.getEnabled() == null) {
            schedule.setEnabled(1);
        }
        bidScheduleMapper.insert(schedule);
        return schedule;
    }

    @Override
    public List<BidSchedule> listBidSchedules(Long shopId) {
        LambdaQueryWrapper<BidSchedule> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(BidSchedule::getShopId, shopId);
        return bidScheduleMapper.selectList(wrapper);
    }
}
