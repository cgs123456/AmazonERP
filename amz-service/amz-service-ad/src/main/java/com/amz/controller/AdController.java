package com.amz.controller;
import com.amz.annotation.ShopScoped;

import com.amz.model.AdReport;
import com.amz.model.BidSchedule;
import com.amz.optimizer.KeywordOptimizer;
import com.amz.scheduler.AdReportSyncScheduler;
import com.amz.result.Result;
import com.amz.service.AdService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 广告管理 REST 端点。
 */
@RestController
@RequestMapping("/ad")
public class AdController {

    @Autowired
    private AdService adService;

    @Autowired
    private AdReportSyncScheduler adReportSyncScheduler;

    /**
     * 查询店铺活动级报表（含 ACoS/ROAS）。
     * GET /ad/report/{shopId}
     */
    @ShopScoped
    @GetMapping("/report/{shopId}")
    public Result<List<AdReport>> getReports(@PathVariable Long shopId) {
        return Result.success(adService.getShopReports(shopId));
    }

    /**
     * 查询店铺整体汇总指标。
     * GET /ad/summary/{shopId}
     */
    @ShopScoped
    @GetMapping("/summary/{shopId}")
    public Result<AdReport> getSummary(@PathVariable Long shopId) {
        return Result.success(adService.getShopSummary(shopId));
    }

    /**
     * 生成关键词优化建议。
     * GET /ad/keyword/optimize?shopId=1&campaignId=camp-001
     */
    @ShopScoped
    @GetMapping("/keyword/optimize")
    public Result<List<KeywordOptimizer.Suggestion>> optimizeKeywords(
            @RequestParam Long shopId,
            @RequestParam(required = false) String campaignId) {
        return Result.success(adService.optimizeKeywords(shopId, campaignId));
    }

    /**
     * 创建分时调价规则。
     * POST /ad/bidSchedule
     */
    @PostMapping("/bidSchedule")
    public Result<BidSchedule> createBidSchedule(@RequestBody BidSchedule schedule) {
        return Result.success(adService.createBidSchedule(schedule));
    }

    /**
     * 查询店铺的分时调价规则列表。
     * GET /ad/bidSchedule/{shopId}
     */
    @ShopScoped
    @GetMapping("/bidSchedule/{shopId}")
    public Result<List<BidSchedule>> listBidSchedules(@PathVariable Long shopId) {
        return Result.success(adService.listBidSchedules(shopId));
    }

    /**
     * 查询店铺广告报表（查询参数版，供 Agent 工具调用）。
     * GET /ad/reports?shopId=1
     */
    @ShopScoped
    @GetMapping("/reports")
    public Result<List<AdReport>> getReportsByShop(@RequestParam Long shopId) {
        if (shopId == null) {
            return Result.failure("shopId must not be null");
        }
        return Result.success(adService.getShopReports(shopId));
    }

    /**
     * 查询店铺近 N 天每日 ACoS 趋势（供前端折线图）。
     * GET /ad/trend?shopId=1&days=14
     * <p>
     * 数据源为 amz_ad_daily_report；表空或未初始化时返回 []，前端保留降级 mock。
     */
    @ShopScoped
    @GetMapping("/trend")
    public Result<List<Map<String, Object>>> getAdTrend(
            @RequestParam(required = false) Long shopId,
            @RequestParam(required = false, defaultValue = "14") Integer days,
            @RequestParam(required = false) String adType) {
        return Result.success(adService.getAdTrend(shopId, days, adType));
    }

    /**
     * 手动触发广告日报同步落库（运维/回补入口，定时任务共用同一逻辑）。
     * POST /ad/reports/sync?shopId=&days=7
     * <p>
     * shopId 为空时同步全部有广告活动配置的店铺；days 缺省 7 天、上限 30 天。
     * shopId 为 null 时切面跳过校验（见 ShopIdGuardAspect），非 null 时按授权校验。
     */
    @ShopScoped
    @PostMapping("/reports/sync")
    public Result<Map<String, Object>> syncReports(
            @RequestParam(required = false) Long shopId,
            @RequestParam(required = false, defaultValue = "7") Integer days) {
        int rows;
        if (shopId != null) {
            rows = adReportSyncScheduler.syncShopReports(shopId, days == null ? 7 : days);
        } else {
            rows = adReportSyncScheduler.syncAllShops(days == null ? 7 : days);
        }
        Map<String, Object> data = new HashMap<>();
        data.put("shopId", shopId);
        data.put("days", days);
        data.put("upserted", rows);
        return Result.success(data);
    }

    /**
     * 竞品价格监控（供 Agent 工具调用）。
     * 注：需接入 SP-API Pricing API 获取真实竞品价格，当前返回占位结构。
     * GET /ad/competitor?shopId=1&asin=B0xxx
     */
    @ShopScoped
    @GetMapping("/competitor")
    public Result<Map<String, Object>> getCompetitor(@RequestParam Long shopId,
                                                      @RequestParam String asin) {
        if (shopId == null || asin == null) {
            return Result.failure("shopId and asin must not be null");
        }
        Map<String, Object> data = new HashMap<>();
        data.put("shopId", shopId);
        data.put("asin", asin);
        data.put("note", "竞品价格监控需接入 SP-API Pricing API，当前返回占位数据");
        data.put("myPrice", null);
        data.put("avgCompetitorPrice", null);
        data.put("lowestCompetitor", null);
        data.put("buyBoxPrice", null);
        return Result.success(data);
    }
}
