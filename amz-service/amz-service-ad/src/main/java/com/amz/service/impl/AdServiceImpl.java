package com.amz.service.impl;

import com.amz.analytics.AdPerformanceAnalyzer;
import com.amz.client.AdvertisingApiClient;
import com.amz.context.UserContext;
import com.amz.exception.AttrIsNullException;
import com.amz.exception.CodeErrorException;
import com.amz.mapper.AdDailyReportMapper;
import com.amz.mapper.AdKeywordMapper;
import com.amz.mapper.BidScheduleMapper;
import com.amz.model.AdDailyReport;
import com.amz.model.AdKeyword;
import com.amz.model.AdReport;
import com.amz.model.BidSchedule;
import com.amz.optimizer.KeywordOptimizer;
import com.amz.result.PageRequest;
import com.amz.result.PageResult;
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

    /** 活动级报表默认统计窗口，与广告首页近 7 天口径保持一致。 */
    private static final int SHOP_REPORT_LOOKBACK_DAYS = 7;

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
    public PageResult<AdReport> getShopReports(Long shopId, PageRequest page) {
        PageRequest req = page == null ? PageRequest.first(PageRequest.DEFAULT_SIZE) : page;
        if (shopId == null) {
            return PageResult.empty(req.size());
        }
        LocalDate end = LocalDate.now();
        LocalDate start = end.minusDays(SHOP_REPORT_LOOKBACK_DAYS - 1L);
        Long cursorId = req.hasCursor() ? req.cursorId() : null;

        final List<AdDailyReport> rows;
        try {
            rows = adDailyReportMapper.aggregateCampaignReports(
                    shopId, start, end, cursorId, req.probeSize());
        } catch (Exception e) {
            throw new IllegalStateException("查询广告日报聚合失败，shopId=" + shopId, e);
        }
        return PageResult.of(rows, req.size(), row -> PageRequest.encodeCursor(row.getId()))
                .map(this::toReport);
    }

    @Override
    public AdReport getShopSummary(Long shopId) {
        if (shopId == null) {
            return null;
        }
        LocalDate end = LocalDate.now();
        LocalDate start = end.minusDays(SHOP_REPORT_LOOKBACK_DAYS - 1L);

        final AdDailyReport summary;
        try {
            summary = adDailyReportMapper.aggregateShopReport(shopId, start, end);
        } catch (Exception e) {
            throw new IllegalStateException("查询广告日报汇总失败，shopId=" + shopId, e);
        }
        return summary == null || summary.getId() == null ? null : toReport(summary);
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
        if (keywords == null || keywords.isEmpty()) {
            return Collections.emptyList();
        }
        // 日报表当前仅按 campaign/date 聚合，不能安全下推到单个关键词。
        // 在引入关键词级报表前只返回 OBSERVE，避免用活动级数据伪造关键词指标。
        return keywordOptimizer.optimize(keywords, Collections.emptyList());
    }

    @Override
    @com.amz.annotation.ShopScoped
    public BidSchedule createBidSchedule(BidSchedule schedule) {
        if (schedule == null || schedule.getShopId() == null) {
            throw new AttrIsNullException("店铺ID不能为空");
        }
        if (!UserContext.isShopAllowedStrict(schedule.getShopId())) {
            throw new CodeErrorException("店铺不存在或无权访问");
        }
        if (schedule.getEnabled() == null) {
            schedule.setEnabled(1);
        }
        bidScheduleMapper.insert(schedule);
        return schedule;
    }

    @Override
    public PageResult<BidSchedule> listBidSchedules(Long shopId, PageRequest page) {
        PageRequest req = page == null ? PageRequest.first(PageRequest.DEFAULT_SIZE) : page;
        LambdaQueryWrapper<BidSchedule> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(BidSchedule::getShopId, shopId);
        if (req.hasCursor()) {
            wrapper.lt(BidSchedule::getId, req.cursorId());
        }
        wrapper.orderByDesc(BidSchedule::getId)
               .last("LIMIT " + req.probeSize());
        List<BidSchedule> rows = bidScheduleMapper.selectList(wrapper);
        return PageResult.of(rows, req.size(), schedule -> PageRequest.encodeCursor(schedule.getId()));
    }

    private AdReport toReport(AdDailyReport row) {
        AdReport report = new AdReport();
        report.setCampaignId(row.getCampaignId());
        report.setImpressions(valueOrZero(row.getImpressions()));
        report.setClicks(valueOrZero(row.getClicks()));
        report.setCost(valueOrZero(row.getCost()));
        report.setSales(valueOrZero(row.getSales()));
        report.setOrders(valueOrZero(row.getOrders()));
        report.setUnits(valueOrZero(row.getUnits()));
        analyzer.fillDerivedMetrics(report);
        return report;
    }

    private static long valueOrZero(Long value) {
        return value == null ? 0L : value;
    }

    private static int valueOrZero(Integer value) {
        return value == null ? 0 : value;
    }

    private static BigDecimal valueOrZero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
