package com.amz.controller;

import com.amz.model.HijackAlert;
import com.amz.model.KeywordRankRecord;
import com.amz.model.NegativeReviewAlert;
import com.amz.result.Result;
import com.amz.service.OpsService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 运营工具 REST 端点：差评/跟卖/关键词排名。
 */
@RestController
@RequestMapping("/ops")
public class OpsController {

    @Autowired
    private OpsService opsService;

    // ========== 差评监控 ==========

    /**
     * 手动触发差评扫描。
     * POST /ops/review/scan/{shopId}
     */
    @PostMapping("/review/scan/{shopId}")
    public Result<Integer> scanReviews(@PathVariable Long shopId) {
        return Result.success(opsService.scanNegativeReviews(shopId));
    }

    /**
     * 查询差评告警列表。
     * GET /ops/review/list/{shopId}?status=
     */
    @GetMapping("/review/list/{shopId}")
    public Result<List<NegativeReviewAlert>> listReviewAlerts(
            @PathVariable Long shopId,
            @RequestParam(required = false) String status) {
        return Result.success(opsService.listNegativeReviewAlerts(shopId, status));
    }

    /**
     * 标记差评告警已处理。
     * POST /ops/review/{alertId}/handle
     */
    @PostMapping("/review/{alertId}/handle")
    public Result<Boolean> handleReviewAlert(@PathVariable Long alertId) {
        return Result.success(opsService.handleNegativeReviewAlert(alertId));
    }

    // ========== 跟卖监控 ==========

    /**
     * 手动触发跟卖扫描。
     * POST /ops/hijack/scan/{shopId}
     */
    @PostMapping("/hijack/scan/{shopId}")
    public Result<Integer> scanHijacks(@PathVariable Long shopId) {
        return Result.success(opsService.scanHijackers(shopId));
    }

    /**
     * 查询跟卖告警列表。
     * GET /ops/hijack/list/{shopId}?status=
     */
    @GetMapping("/hijack/list/{shopId}")
    public Result<List<HijackAlert>> listHijackAlerts(
            @PathVariable Long shopId,
            @RequestParam(required = false) String status) {
        return Result.success(opsService.listHijackAlerts(shopId, status));
    }

    // ========== 关键词排名追踪 ==========

    /**
     * 手动触发关键词排名抓取。
     * POST /ops/rank/capture/{shopId}
     */
    @PostMapping("/rank/capture/{shopId}")
    public Result<Integer> captureRanks(@PathVariable Long shopId) {
        return Result.success(opsService.captureKeywordRanks(shopId));
    }

    /**
     * 查询关键词排名趋势。
     * GET /ops/rank/trend?shopId=&keyword=&asin=
     */
    @GetMapping("/rank/trend")
    public Result<List<KeywordRankRecord>> getRankTrend(
            @RequestParam Long shopId,
            @RequestParam String keyword,
            @RequestParam String asin) {
        return Result.success(opsService.getRankTrend(shopId, keyword, asin));
    }
}
