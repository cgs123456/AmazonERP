package com.amz.controller;
import com.amz.annotation.RequireRole;
import com.amz.annotation.ShopScoped;
import com.amz.context.UserContext;

import com.amz.model.AdReport;
import com.amz.model.BidSchedule;
import com.amz.optimizer.KeywordOptimizer;
import com.amz.scheduler.AdReportSyncScheduler;
import com.amz.result.PageRequest;
import com.amz.result.Result;
import com.amz.service.AdService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

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
    public Result<List<AdReport>> getReports(@PathVariable Long shopId,
                                              @RequestParam(required = false) Integer size,
                                              @RequestParam(required = false) String cursor) {
        return Result.paged(adService.getShopReports(shopId, PageRequest.of(size, cursor)));
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
    @RequireRole({"OPERATOR", "ADMIN"})
    @PostMapping("/bidSchedule")
    public Result<BidSchedule> createBidSchedule(@RequestBody BidSchedule schedule) {
        if (schedule == null || schedule.getShopId() == null) {
            return Result.failure("shopId 不能为空");
        }
        // shopId 位于请求体中，@ShopScoped 无法从方法参数解析；必须在调用服务前显式校验。
        // Service 内仍保留严格校验，形成纵深防御。
        if (!UserContext.isShopAllowedStrict(schedule.getShopId())) {
            return Result.failure("无权访问该店铺");
        }
        return Result.success(adService.createBidSchedule(schedule));
    }

    /**
     * 查询店铺的分时调价规则列表。
     * GET /ad/bidSchedule/{shopId}
     */
    @ShopScoped
    @GetMapping("/bidSchedule/{shopId}")
    public Result<List<BidSchedule>> listBidSchedules(@PathVariable Long shopId,
                                                       @RequestParam(required = false) Integer size,
                                                       @RequestParam(required = false) String cursor) {
        return Result.paged(adService.listBidSchedules(shopId, PageRequest.of(size, cursor)));
    }

    /**
     * 修改分时调价规则。
     * PUT /ad/bidSchedule/{id}
     * <p>
     * 路径里没有 shopId，{@code @ShopScoped} 解析不到参数会直接放行，
     * 归属由 Service 侧按 id 反查后严格校验（与广告规则端点同一套做法）。
     */
    @RequireRole({"OPERATOR", "ADMIN"})
    @ShopScoped
    @PutMapping("/bidSchedule/{id}")
    public Result<BidSchedule> updateBidSchedule(@PathVariable Long id,
                                                  @RequestBody BidSchedule schedule) {
        if (schedule == null) {
            return Result.failure("请求体不能为空");
        }
        schedule.setId(id);
        return Result.success(adService.updateBidSchedule(schedule));
    }

    /** 启用/停用分时调价规则：停用后下一个整点起不再改价 */
    @RequireRole({"OPERATOR", "ADMIN"})
    @ShopScoped
    @PostMapping("/bidSchedule/{id}/toggle")
    public Result<Boolean> toggleBidSchedule(@PathVariable Long id, @RequestParam boolean enabled) {
        return Result.success(adService.toggleBidSchedule(id, enabled));
    }

    /** 删除分时调价规则 */
    @RequireRole({"OPERATOR", "ADMIN"})
    @ShopScoped
    @DeleteMapping("/bidSchedule/{id}")
    public Result<Boolean> deleteBidSchedule(@PathVariable Long id) {
        return Result.success(adService.deleteBidSchedule(id));
    }

    /**
     * 查询店铺广告报表（查询参数版，供 Agent 工具调用）。
     * GET /ad/reports?shopId=1
     */
    @ShopScoped
    @GetMapping("/reports")
    public Result<List<AdReport>> getReportsByShop(@RequestParam Long shopId,
                                                    @RequestParam(required = false) Integer size,
                                                    @RequestParam(required = false) String cursor) {
        if (shopId == null) {
            return Result.failure("shopId must not be null");
        }
        return Result.paged(adService.getShopReports(shopId, PageRequest.of(size, cursor)));
    }

    /**
     * 查询店铺近 N 天每日 ACoS 趋势（供前端折线图）。
     * GET /ad/trend?shopId=1&days=14
     * <p>
     * 数据源为 amz_ad_daily_report；表空或未初始化时返回 []，前端生产模式展示空态。
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
     * 手动触发单店铺广告日报同步（操作入口）。
     * POST /ad/reports/sync?shopId=101&days=7
     * <p>
     * 仅 OPERATOR/ADMIN 可调用；shopId 必须显式提供并通过 {@code @ShopScoped} 授权校验。
     * 缺少 shopId 的请求不会进入本方法，避免单店入口退化为全店铺同步。
     */
    @RequireRole({"OPERATOR", "ADMIN"})
    @ShopScoped
    @PostMapping(value = "/reports/sync", params = "shopId")
    public Result<Map<String, Object>> syncReports(
            @RequestParam Long shopId,
            @RequestParam(required = false, defaultValue = "7") Integer days) {
        if (shopId == null) {
            return Result.failure("shopId must not be null");
        }
        int effectiveDays = days == null ? 7 : days;
        return syncResponse(shopId, effectiveDays,
                adReportSyncScheduler.syncShopReportsWithSummary(shopId, effectiveDays));
    }

    /**
     * 手动触发全店铺广告日报同步（管理员运维/回补入口）。
     * POST /ad/reports/sync?days=7
     */
    @RequireRole({"ADMIN"})
    @PostMapping(value = "/reports/sync", params = "!shopId")
    public Result<Map<String, Object>> syncAllReports(
            @RequestParam(required = false, defaultValue = "7") Integer days) {
        int effectiveDays = days == null ? 7 : days;
        return syncResponse(null, effectiveDays,
                adReportSyncScheduler.syncAllShopsWithSummary(effectiveDays));
    }

    private Result<Map<String, Object>> syncResponse(Long shopId, int days,
                                                      AdReportSyncScheduler.SyncSummary summary) {
        Map<String, Object> data = new HashMap<>();
        data.put("shopId", shopId);
        data.put("days", days);
        data.put("attempted", summary.attempted());
        data.put("succeeded", summary.succeeded());
        data.put("failed", summary.failed());
        data.put("skipped", summary.skipped());
        data.put("upserted", summary.upserted());
        data.put("metadataWarnings", summary.metadataWarnings());
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
