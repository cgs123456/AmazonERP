package com.amz.controller;

import com.amz.annotation.OperLog;
import com.amz.annotation.RequireRole;
import com.amz.annotation.ShopScoped;
import com.amz.model.ShopPerformance;
import com.amz.result.Result;
import com.amz.service.ShopPerformanceSyncHandler;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 店铺绩效接口（P3.1 原始留痕版）。
 * <p>
 * <b>诚实边界</b>：GET_V2_SELLER_PERFORMANCE_REPORT 官方存在，但本仓快照无列结构，
 * 因此做原文留痕而非字段级解析；解析器等真实样本后补。
 */
@Slf4j
@RestController
@RequestMapping("/spapi/performance")
public class PerformanceController {

    @Autowired
    private com.amz.client.ReportsClient reportsClient;

    @Autowired
    private com.amz.mapper.ShopPerformanceMapper performanceMapper;

    /** 轮询参数（默认与结算同步同量级）。 */
    @Value("${amz.spapi.performance.max-poll-attempts:15}")
    private int maxPollAttempts = 15;

    @Value("${amz.spapi.performance.poll-interval-ms:2000}")
    private long pollIntervalMs = 2000;

    @Value("${amz.spapi.performance.poll-deadline-ms:90000}")
    private long pollDeadlineMs = 90000;

    /**
     * 手动同步：拉取绩效报表原文入库。
     */
    @ShopScoped
    @RequireRole({"OPERATOR", "ADMIN"})
    @OperLog(module = "店铺绩效", action = "同步", description = "拉取绩效报表原文入库")
    @PostMapping("/sync/{shopId}")
    public Result<ShopPerformance> sync(@PathVariable Long shopId,
                                        @RequestParam String marketplaceId,
                                        @RequestParam(defaultValue = "30") Integer windowDays) {
        try {
            ShopPerformanceSyncHandler handler = new ShopPerformanceSyncHandler(
                    reportsClient, performanceMapper,
                    maxPollAttempts, pollIntervalMs, pollDeadlineMs);
            return Result.success(handler.syncShop(shopId, marketplaceId, windowDays));
        } catch (Exception e) {
            log.error("绩效同步失败 shopId={}", shopId, e);
            return Result.failure("绩效同步失败：" + com.amz.connector.ErrorSummary.of(e),
                    com.amz.connector.ErrorSummary.toApiError(e));
        }
    }

    /**
     * 绩效留痕历史（最近 50 条，不含原文）。
     */
    @ShopScoped
    @GetMapping("/list/{shopId}")
    public Result<List<ShopPerformance>> list(@PathVariable Long shopId) {
        return Result.success(performanceMapper.selectList(
                new LambdaQueryWrapper<ShopPerformance>()
                        .eq(ShopPerformance::getShopId, shopId)
                        .orderByDesc(ShopPerformance::getId)
                        .last("LIMIT 50")));
    }

    /**
     * 留痕详情（含原文）。
     */
    @ShopScoped
    @GetMapping("/{id}")
    public Result<ShopPerformance> detail(@PathVariable Long id) {
        return Result.success(performanceMapper.selectById(id));
    }
}
