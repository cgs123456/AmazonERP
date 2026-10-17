package com.amz.controller;

import com.amz.annotation.OperLog;
import com.amz.annotation.RequireRole;
import com.amz.annotation.ShopScoped;
import com.amz.connector.ErrorSummary;
import com.amz.connector.LocalApiException;
import com.amz.context.UserContext;
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
     *
     * <p><b>为什么这里的 {@code @ShopScoped} 是空的</b>：切面只认名为 {@code shopId} 的
     * {@code @RequestParam}/{@code @PathVariable} 参数（{@code ShopIdParameterResolver}），
     * 而本方法的路径参数叫 {@code id}——解析不到就按「兼容放行」处理。
     * 也就是说这枚注解在这里不设防，与 {@code /sync/{shopId}}、{@code /list/{shopId}}
     * 那两条不是一回事。
     *
     * <p>后果不是「看不到」而是「看得到别人的」：{@code amz_shop_performance.id} 是
     * 自增主键，{@code raw_content} 是整份 SP-API 报表原文（含该店绩效数据）。
     * 任何已登录用户按 id 递增就能逐条读遍所有店铺的原文。
     *
     * <p>因此归属判定必须在方法内逐行做，且用严格档——本端点没有
     * {@code @InternalServiceAccess}，不存在需要宽松档兼容的内部调用方。
     * 越权与不存在共用同一句文案：否则这个端点会退化成「哪条留痕存在」的探针。
     */
    @RequireRole({"VIEWER", "OPERATOR", "ADMIN"})
    @GetMapping("/{id}")
    public Result<ShopPerformance> detail(@PathVariable Long id) {
        if (id == null) {
            return Result.failure("留痕 ID 不能为空",
                    ErrorSummary.localError(LocalApiException.CODE_INVALID_REQUEST));
        }
        ShopPerformance row = performanceMapper.selectById(id);
        if (row == null || !UserContext.isShopAllowedStrict(row.getShopId())) {
            log.warn("[performance] 留痕详情越权或不存在：userId={} role={} 授权店铺={} id={}",
                    UserContext.getUserId(), UserContext.getRole(), UserContext.getShops(), id);
            // 越权与不存在共用同一句文案与同一个 code：分开就能把这个端点当成
            // 「哪条留痕存在」的探针，按 id 递增摸清全表规模。
            return Result.failure("留痕不存在或无权访问",
                    ErrorSummary.localError(LocalApiException.CODE_FORBIDDEN));
        }
        return Result.success(row);
    }
}
