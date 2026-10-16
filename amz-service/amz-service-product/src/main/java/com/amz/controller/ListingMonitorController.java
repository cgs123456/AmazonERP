package com.amz.controller;

import com.amz.annotation.OperLog;
import com.amz.annotation.RequireRole;
import com.amz.annotation.ShopScoped;
import com.amz.model.BuyBox;
import com.amz.model.CompetitorMonitor;
import com.amz.model.KeywordRanking;
import com.amz.model.ListingChangeLog;
import com.amz.model.ListingHealth;
import com.amz.result.Result;
import com.amz.service.ListingMonitorService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

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

    /**
     * 触发 Listing 健康检查（人工登记一次自查；每次会把结果 upsert 进 amz_listing_health，
     * 所以它是一个写端点，不是查询）。
     * <p>
     * shopId 在查询参数里，两个切面都能解析到。
     */
    @RequireRole({"OPERATOR", "ADMIN"})
    @OperLog(module = "Listing 监控", action = "自查登记", description = "登记 Listing 健康自查")
    @ShopScoped
    @PostMapping("/health/check")
    public Result<ListingHealth> checkListing(@RequestParam Long shopId,
                                              @RequestParam String asin,
                                              @RequestParam(required = false) String title,
                                              @RequestParam(required = false) String bullets,
                                              @RequestParam(required = false) String description,
                                              @RequestParam(required = false) Integer imageCount,
                                              @RequestParam(required = false) String searchTerms,
                                              @RequestParam(required = false) String status,
                                              @RequestParam(required = false) Boolean aplus) {
        // 这是「人工登记一次自查」：所有判定项都来自调用方填的内容，服务并没有去读 Listing 本体，
        // A+ 不给值就记为未知（原先无条件写成正常）。
        return Result.success(listingMonitorService.checkListing(shopId, asin, title, bullets,
                description, imageCount, searchTerms, status, aplus));
    }

    /**
     * 关键词排名登记。
     * <p>
     * 本端点此前只有 {@code @ShopScoped}，而 shopId 只在请求体（{@code KeywordRanking.shopId}）里——
     * 切面解析不到参数就按「未找到 shopId 参数 → 兼容放行」跳过，而 service 层也没有任何行级校验，
     * 于是任何已登录用户（含 VIEWER）都能往任意店铺写入。两处同时补上：
     * 角色门禁按请求体里的店铺判定，租户归属由切面校验（现在也能读到请求体了）。
     */
    @RequireRole({"OPERATOR", "ADMIN"})
    @OperLog(module = "Listing 监控", action = "登记排名", description = "保存关键词排名")
    @ShopScoped
    @PostMapping("/ranking")
    public Result<KeywordRanking> saveRanking(@RequestBody KeywordRanking ranking) {
        return Result.success(listingMonitorService.saveRanking(ranking));
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

    /**
     * 保存竞品快照。
     * <p>
     * 与 saveRanking 同因：shopId 只在请求体里，此前两处防线同时让开。
     * 注意定时任务 {@code KeepaCompetitorScheduler} 直接走 mapper，不经本端点，不受影响。
     */
    @RequireRole({"OPERATOR", "ADMIN"})
    @OperLog(module = "Listing 监控", action = "登记竞品", description = "保存竞品快照")
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

    /**
     * 保存 BuyBox 快照。
     * <p>
     * 与 saveRanking 同因：shopId 只在请求体里，此前两处防线同时让开。
     */
    @RequireRole({"OPERATOR", "ADMIN"})
    @OperLog(module = "Listing 监控", action = "登记 BuyBox", description = "保存 BuyBox 快照")
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
