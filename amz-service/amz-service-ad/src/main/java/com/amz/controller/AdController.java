package com.amz.controller;

import com.amz.model.AdReport;
import com.amz.model.BidSchedule;
import com.amz.optimizer.KeywordOptimizer;
import com.amz.result.Result;
import com.amz.service.AdService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 广告管理 REST 端点。
 */
@RestController
@RequestMapping("/ad")
public class AdController {

    @Autowired
    private AdService adService;

    /**
     * 查询店铺活动级报表（含 ACoS/ROAS）。
     * GET /ad/report/{shopId}
     */
    @GetMapping("/report/{shopId}")
    public Result<List<AdReport>> getReports(@PathVariable Long shopId) {
        return Result.success(adService.getShopReports(shopId));
    }

    /**
     * 查询店铺整体汇总指标。
     * GET /ad/summary/{shopId}
     */
    @GetMapping("/summary/{shopId}")
    public Result<AdReport> getSummary(@PathVariable Long shopId) {
        return Result.success(adService.getShopSummary(shopId));
    }

    /**
     * 生成关键词优化建议。
     * GET /ad/keyword/optimize?shopId=1&campaignId=camp-001
     */
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
    @GetMapping("/bidSchedule/{shopId}")
    public Result<List<BidSchedule>> listBidSchedules(@PathVariable Long shopId) {
        return Result.success(adService.listBidSchedules(shopId));
    }
}
