package com.amz.service.impl;

import com.amz.analytics.AdPerformanceAnalyzer;
import com.amz.client.AdvertisingApiClient;
import com.amz.context.UserContext;
import com.amz.exception.AttrIsNullException;
import com.amz.exception.CodeErrorException;
import com.amz.exception.InvalidParamException;
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
import java.util.Objects;
import java.util.TreeMap;

/**
 * 广告管理服务实现。
 */
@Service
public class AdServiceImpl implements AdService {

    private static final Logger log = LoggerFactory.getLogger(AdServiceImpl.class);

    /** 活动级报表默认统计窗口，与广告首页近 7 天口径保持一致。 */
    private static final int SHOP_REPORT_LOOKBACK_DAYS = 7;

    /**
     * 分时调价倍率的允许区间。执行器只兜「绝对价」的上下限（0.02~1000），
     * 一条 50x 的规则和一条手滑写错的规则在它眼里没有区别，所以这里按业务口径拦一次。
     * 参考 V1 预置的三条：0.70 / 1.20 / 1.50。
     */
    private static final BigDecimal MIN_MULTIPLIER = new BigDecimal("0.10");
    private static final BigDecimal MAX_MULTIPLIER = new BigDecimal("5.00");

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
        requireValidWindow(schedule);
        if (schedule.getEnabled() == null) {
            schedule.setEnabled(1);
        }
        bidScheduleMapper.insert(schedule);
        log.info("分时调价规则已创建：shopId={} campaignId={} 窗口={}-{} 倍率={}",
                schedule.getShopId(), schedule.getCampaignId(),
                schedule.getStartHour(), schedule.getEndHour(), schedule.getMultiplier());
        return schedule;
    }

    @Override
    public BidSchedule updateBidSchedule(BidSchedule schedule) {
        if (schedule == null || schedule.getId() == null) {
            throw new AttrIsNullException("规则ID不能为空");
        }
        BidSchedule existing = requireScheduleAccess(schedule.getId());
        if (schedule.getShopId() != null && !Objects.equals(schedule.getShopId(), existing.getShopId())) {
            throw new CodeErrorException("调价规则所属店铺不可修改");
        }
        schedule.setShopId(existing.getShopId());
        // PUT 允许只带被改的字段，所以校验的是落库后真正生效的形态：
        // 只把 startHour 从 0 改成 20、endHour 仍是库里的 6，合并后才是那条永不命中的死规则。
        requireValidWindow(mergeForValidation(existing, schedule));
        bidScheduleMapper.updateById(schedule);
        return schedule;
    }

    @Override
    public boolean toggleBidSchedule(Long id, boolean enabled) {
        BidSchedule schedule = requireScheduleAccess(id);
        schedule.setEnabled(enabled ? 1 : 0);
        bidScheduleMapper.updateById(schedule);
        log.info("分时调价规则已{}：id={} shopId={}", enabled ? "启用" : "停用", id, schedule.getShopId());
        return true;
    }

    @Override
    public boolean deleteBidSchedule(Long id) {
        BidSchedule schedule = requireScheduleAccess(id);
        bidScheduleMapper.deleteById(id);
        log.info("分时调价规则已删除：id={} shopId={}", id, schedule.getShopId());
        return true;
    }

    /**
     * 分时调价是唯一会真实改广告账号 bid 的通道，所以「写了但永远不会执行」和
     * 「写了就立刻按小时改价」都必须拦在落库前。
     */
    private static void requireValidWindow(BidSchedule schedule) {
        Integer start = schedule.getStartHour();
        Integer end = schedule.getEndHour();
        if (start == null || end == null) {
            throw new InvalidParamException("起始小时与结束小时都不能为空");
        }
        if (start < 0 || start > 23 || end < 0 || end > 23) {
            throw new InvalidParamException("小时必须在 0-23 之间，实际 " + start + "-" + end);
        }
        if (start > end) {
            throw new InvalidParamException(
                    "暂不支持跨零点窗口：调度按 start_hour <= 当前小时 <= end_hour 命中，"
                            + "写成 " + start + "-" + end + " 的规则永远不会生效");
        }
        BigDecimal multiplier = schedule.getMultiplier();
        if (multiplier == null) {
            throw new InvalidParamException("竞价倍率不能为空");
        }
        if (multiplier.compareTo(MIN_MULTIPLIER) < 0 || multiplier.compareTo(MAX_MULTIPLIER) > 0) {
            throw new InvalidParamException("竞价倍率必须在 "
                    + MIN_MULTIPLIER.toPlainString() + "-" + MAX_MULTIPLIER.toPlainString()
                    + " 之间，实际 " + multiplier.toPlainString()
                    + "；执行器只兜绝对价上下限，认不出这是不是一次手滑");
        }
    }

    private static BidSchedule mergeForValidation(BidSchedule existing, BidSchedule patch) {
        BidSchedule merged = new BidSchedule();
        merged.setId(patch.getId());
        merged.setShopId(existing.getShopId());
        merged.setCampaignId(patch.getCampaignId() != null ? patch.getCampaignId() : existing.getCampaignId());
        merged.setStartHour(patch.getStartHour() != null ? patch.getStartHour() : existing.getStartHour());
        merged.setEndHour(patch.getEndHour() != null ? patch.getEndHour() : existing.getEndHour());
        merged.setMultiplier(patch.getMultiplier() != null ? patch.getMultiplier() : existing.getMultiplier());
        merged.setEnabled(patch.getEnabled() != null ? patch.getEnabled() : existing.getEnabled());
        return merged;
    }

    private BidSchedule requireScheduleAccess(Long id) {
        if (id == null) {
            throw new AttrIsNullException("规则ID不能为空");
        }
        BidSchedule schedule = bidScheduleMapper.selectById(id);
        if (schedule == null || !UserContext.isShopAllowedStrict(schedule.getShopId())) {
            log.warn("分时调价越权拦截：userId={}, scheduleId={}", UserContext.getUserId(), id);
            throw new CodeErrorException("调价规则不存在或无权访问");
        }
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
