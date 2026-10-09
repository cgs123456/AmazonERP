package com.amz.controller;

import com.amz.annotation.ShopScoped;
import com.amz.model.HijackAlert;
import com.amz.model.KeywordRankRecord;
import com.amz.model.NegativeReviewAlert;
import com.amz.result.PageRequest;
import com.amz.result.Result;
import com.amz.service.OpsService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

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
    @ShopScoped
    @PostMapping("/review/scan/{shopId}")
    public Result<Integer> scanReviews(@PathVariable Long shopId) {
        return Result.success(opsService.scanNegativeReviews(shopId));
    }

    /**
     * 查询差评告警列表。
     * GET /ops/review/list/{shopId}?status=&size=&cursor=
     */
    @ShopScoped
    @GetMapping("/review/list/{shopId}")
    public Result<List<NegativeReviewAlert>> listReviewAlerts(
            @PathVariable Long shopId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String cursor) {
        return Result.paged(opsService.listNegativeReviewAlerts(shopId, status, PageRequest.of(size, cursor)));
    }

    /**
     * 标记差评告警已处理。
     * POST /ops/review/{alertId}/handle
     *
     * <p>这里没有 {@code @ShopScoped}：切面只认名为 shopId 的 path/query 参数，而这条端点
     * 只有 alertId，加上去也不会生效。归属判定在 service 内逐行做（严格档），
     * 越权与不存在统一文案，避免这条端点变成告警 ID 的存在性探针。
     */
    @PostMapping("/review/{alertId}/handle")
    public Result<Boolean> handleReviewAlert(@PathVariable Long alertId) {
        return Result.success(opsService.handleNegativeReviewAlert(alertId));
    }

    /**
     * 标记差评告警已忽略（NEW → IGNORED）。
     * POST /ops/review/{alertId}/ignore
     *
     * <p>DDL 的 status 注释是 NEW/HANDLED/IGNORED，但此前 IGNORED 没有任何写入路径，
     * 运营只能把「不打算处理」的告警标成 HANDLED，等于在库里留下一个假的处理记录。
     * 这条端点把第三态接上；与 handle 共用同一套归属与终态判定。
     */
    @PostMapping("/review/{alertId}/ignore")
    public Result<Boolean> ignoreReviewAlert(@PathVariable Long alertId) {
        return Result.success(opsService.ignoreNegativeReviewAlert(alertId));
    }

    // ========== 跟卖监控 ==========

    /**
     * 手动触发跟卖扫描。
     * POST /ops/hijack/scan/{shopId}
     */
    @ShopScoped
    @PostMapping("/hijack/scan/{shopId}")
    public Result<Integer> scanHijacks(@PathVariable Long shopId) {
        return Result.success(opsService.scanHijackers(shopId));
    }

    /**
     * 标记跟卖告警已处理（NEW → HANDLED）。
     * POST /ops/hijack/{alertId}/handle
     *
     * <p>与差评的 handle 同形态：没有 {@code @ShopScoped}（只有 alertId），
     * 归属判定在 service 内逐行严格做。
     */
    @PostMapping("/hijack/{alertId}/handle")
    public Result<Boolean> handleHijackAlert(@PathVariable Long alertId) {
        return Result.success(opsService.handleHijackAlert(alertId));
    }

    /**
     * 标记跟卖告警已忽略（NEW → IGNORED）。
     * POST /ops/hijack/{alertId}/ignore
     */
    @PostMapping("/hijack/{alertId}/ignore")
    public Result<Boolean> ignoreHijackAlert(@PathVariable Long alertId) {
        return Result.success(opsService.ignoreHijackAlert(alertId));
    }

    /**
     * 查询跟卖告警列表。
     * GET /ops/hijack/list/{shopId}?status=&size=&cursor=
     */
    @ShopScoped
    @GetMapping("/hijack/list/{shopId}")
    public Result<List<HijackAlert>> listHijackAlerts(
            @PathVariable Long shopId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String cursor) {
        return Result.paged(opsService.listHijackAlerts(shopId, status, PageRequest.of(size, cursor)));
    }

    // ========== 关键词排名追踪 ==========

    /**
     * 手动触发关键词排名抓取。
     * POST /ops/rank/capture/{shopId}
     */
    @ShopScoped
    @PostMapping("/rank/capture/{shopId}")
    public Result<Integer> captureRanks(@PathVariable Long shopId) {
        return Result.success(opsService.captureKeywordRanks(shopId));
    }

    /**
     * 查询关键词排名趋势。
     * GET /ops/rank/trend?shopId=&keyword=&asin=
     */
    @ShopScoped
    @GetMapping("/rank/trend")
    public Result<List<KeywordRankRecord>> getRankTrend(
            @RequestParam Long shopId,
            @RequestParam String keyword,
            @RequestParam String asin) {
        return Result.success(opsService.getRankTrend(shopId, keyword, asin));
    }
}
