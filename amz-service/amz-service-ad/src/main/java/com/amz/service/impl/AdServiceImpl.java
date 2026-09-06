package com.amz.service.impl;

import com.amz.analytics.AdPerformanceAnalyzer;
import com.amz.client.AdvertisingApiClient;
import com.amz.mapper.AdDailyReportMapper;
import com.amz.mapper.AdKeywordMapper;
import com.amz.mapper.BidScheduleMapper;
import com.amz.model.AdDailyReport;
import com.amz.model.AdKeyword;
import com.amz.model.AdReport;
import com.amz.model.BidSchedule;
import com.amz.optimizer.KeywordOptimizer;
import com.amz.service.AdService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 广告管理服务实现。
 */
@Service
public class AdServiceImpl implements AdService {

    private static final Logger log = LoggerFactory.getLogger(AdServiceImpl.class);

    @Autowired
    private AdPerformanceAnalyzer analyzer;

    @Autowired
    private AdDailyReportMapper adDailyReportMapper;

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
    public List<Map<String, Object>> getAdTrend(Long shopId, Integer days, String adType) {
        if (shopId == null) {
            return Collections.emptyList();
        }
        int n = (days == null || days <= 0) ? 14 : Math.min(days, 90);
        LocalDate end = LocalDate.now();
        LocalDate start = end.minusDays(n - 1L);

        final List<AdDailyReport> rows;
        try {
            LambdaQueryWrapper<AdDailyReport> qw = new LambdaQueryWrapper<AdDailyReport>()
                    .eq(AdDailyReport::getShopId, shopId)
                    .ge(AdDailyReport::getReportDate, start)
                    .le(AdDailyReport::getReportDate, end);
            if (adType != null && !adType.isBlank()) {
                qw.eq(AdDailyReport::getAdType, adType);
            }
            rows = adDailyReportMapper.selectList(qw);
        } catch (Exception e) {
            // 日报表可能尚未初始化（如未执行建表语句），降级为空趋势，调用方保留 mock 展示
            log.warn("查询广告日报失败，返回空趋势 shopId={}", shopId, e);
            return Collections.emptyList();
        }

        // 按日期汇总 cost/sales（下标 0=花费，1=销售额）
        Map<LocalDate, BigDecimal[]> daily = new TreeMap<>();
        for (AdDailyReport r : rows) {
            if (r == null || r.getReportDate() == null) {
                continue;
            }
            BigDecimal[] acc = daily.computeIfAbsent(r.getReportDate(),
                    k -> new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO});
            if (r.getCost() != null) {
                acc[0] = acc[0].add(r.getCost());
            }
            if (r.getSales() != null) {
                acc[1] = acc[1].add(r.getSales());
            }
        }

        // 无数据的日期直接跳过（不断轴补 0，避免把 ACoS 曲线拉穿）
        List<Map<String, Object>> result = new ArrayList<>(daily.size());
        for (Map.Entry<LocalDate, BigDecimal[]> e : daily.entrySet()) {
            BigDecimal spend = e.getValue()[0];
            BigDecimal sales = e.getValue()[1];
            BigDecimal acos = sales.compareTo(BigDecimal.ZERO) > 0
                    ? spend.divide(sales, 4, RoundingMode.HALF_UP)
                            .multiply(new BigDecimal("100")).setScale(2, RoundingMode.HALF_UP)
                    : BigDecimal.ZERO;
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("day", e.getKey().toString());
            item.put("value", acos);
            result.add(item);
        }
        return result;
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
