package com.amz.controller;

import com.amz.annotation.ShopScoped;
import com.amz.model.*;
import com.amz.result.Result;
import com.amz.service.ListingMonitorService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Listing 健康监控 REST 端点。
 */
@RestController
@RequestMapping("/product/listing-monitor")
public class ListingMonitorController {

    @Autowired
    private ListingMonitorService listingMonitorService;

    // ==================== Listing 健康度 ====================

    /** 触发 Listing 健康检查 */
    @ShopScoped
    @PostMapping("/health/check")
    public Result<ListingHealth> checkListing(@RequestParam Long shopId,
                                              @RequestParam String asin,
                                              @RequestParam(required = false) String title,
                                              @RequestParam(required = false) String bullets,
                                              @RequestParam(required = false) String description,
                                              @RequestParam(required = false) Integer imageCount,
                                              @RequestParam(required = false) String searchTerms,
                                              @RequestParam(required = false) String status) {
        return Result.success(listingMonitorService.checkListing(shopId, asin, title, bullets,
                description, imageCount, searchTerms, status));
    }

    /** 查询 Listing 健康度列表 */
    @ShopScoped
    @GetMapping("/health/list/{shopId}")
    public Result<List<ListingHealth>> listHealth(@PathVariable Long shopId,
                                                   @RequestParam(required = false) String severity) {
        return Result.success(listingMonitorService.listHealth(shopId, severity));
    }

    /** Listing 健康度汇总 */
    @ShopScoped
    @GetMapping("/health/summary/{shopId}")
    public Result<Map<String, Object>> healthSummary(@PathVariable Long shopId) {
        return Result.success(listingMonitorService.healthSummary(shopId));
    }

    // ==================== 变更日志 ====================

    /** 查询变更日志 */
    @ShopScoped
    @GetMapping("/change-log/list/{shopId}")
    public Result<List<ListingChangeLog>> listChangeLogs(@PathVariable Long shopId,
                                                          @RequestParam(required = false) String asin,
                                                          @RequestParam(required = false) String fieldName) {
        return Result.success(listingMonitorService.listChangeLogs(shopId, asin, fieldName));
    }

    // ==================== 关键词排名 ====================

    /** 保存关键词排名 */
    @ShopScoped
    @PostMapping("/ranking")
    public Result<KeywordRanking> saveRanking(@RequestBody KeywordRanking ranking) {
        return Result.success(listingMonitorService.saveRanking(ranking));
    }

    /** 查询关键词排名列表 */
    @ShopScoped
    @GetMapping("/ranking/list/{shopId}")
    public Result<List<KeywordRanking>> listRankings(@PathVariable Long shopId,
                                                      @RequestParam(required = false) String asin,
                                                      @RequestParam(required = false) String keyword) {
        return Result.success(listingMonitorService.listRankings(shopId, asin, keyword));
    }

    /** 关键词排名趋势 */
    @ShopScoped
    @GetMapping("/ranking/trend/{shopId}")
    public Result<Map<String, Object>> rankingTrend(@PathVariable Long shopId,
                                                     @RequestParam String asin,
                                                     @RequestParam(required = false) String keyword,
                                                     @RequestParam(defaultValue = "30") Integer days) {
        return Result.success(listingMonitorService.rankingTrend(shopId, asin, keyword, days));
    }

    // ==================== 竞品监控 ====================

    /** 保存竞品快照 */
    @ShopScoped
    @PostMapping("/competitor")
    public Result<CompetitorMonitor> saveCompetitor(@RequestBody CompetitorMonitor monitor) {
        return Result.success(listingMonitorService.saveCompetitor(monitor));
    }

    /** 查询竞品列表 */
    @ShopScoped
    @GetMapping("/competitor/list/{shopId}")
    public Result<List<CompetitorMonitor>> listCompetitors(@PathVariable Long shopId,
                                                            @RequestParam(required = false) String competitorAsin) {
        return Result.success(listingMonitorService.listCompetitors(shopId, competitorAsin));
    }

    /** 竞品对比分析 */
    @ShopScoped
    @GetMapping("/competitor/compare/{shopId}")
    public Result<Map<String, Object>> competitorComparison(@PathVariable Long shopId,
                                                             @RequestParam String myAsin,
                                                             @RequestParam String competitorAsin,
                                                             @RequestParam(defaultValue = "30") Integer days) {
        return Result.success(listingMonitorService.competitorComparison(shopId, myAsin, competitorAsin, days));
    }

    // ==================== Buy Box ====================

    /** 保存 BuyBox 快照 */
    @ShopScoped
    @PostMapping("/buybox")
    public Result<BuyBox> saveBuyBox(@RequestBody BuyBox buyBox) {
        return Result.success(listingMonitorService.saveBuyBox(buyBox));
    }

    /** 查询 BuyBox 状态列表 */
    @ShopScoped
    @GetMapping("/buybox/list/{shopId}")
    public Result<List<BuyBox>> listBuyBox(@PathVariable Long shopId,
                                            @RequestParam(required = false) String asin) {
        return Result.success(listingMonitorService.listBuyBox(shopId, asin));
    }

    /** BuyBox 汇总 */
    @ShopScoped
    @GetMapping("/buybox/summary/{shopId}")
    public Result<Map<String, Object>> buyBoxSummary(@PathVariable Long shopId) {
        return Result.success(listingMonitorService.buyBoxSummary(shopId));
    }
}
