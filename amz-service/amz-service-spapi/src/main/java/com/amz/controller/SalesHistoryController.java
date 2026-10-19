package com.amz.controller;

import com.amz.annotation.OperLog;
import com.amz.annotation.RequireRole;
import com.amz.annotation.ShopScoped;
import com.amz.connector.ErrorSummary;
import com.amz.connector.LocalApiException;
import com.amz.credential.ShopCredential;
import com.amz.credential.ShopCredentialStore;
import com.amz.mapper.SalesHistoryMapper;
import com.amz.model.SalesHistory;
import com.amz.result.Result;
import com.amz.service.impl.SalesHistorySyncService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * 销售历史接口（手动同步 + 查询）。
 * <p>
 * <b>为什么需要手动入口</b>：定时同步默认关闭，而「补货建议基于空历史出数」
 * 是运营最容易误判的场景——页面上的日均销量显示 0，看代码看不出是「真的没卖」
 * 还是「历史没同步」。这条入口让运营能在建补货计划前先同步一次并**看见行数**，
 * 不必等第二天 04:20 的调度。
 * <p>
 * <b>与定时任务共用同一服务</b>：手动触发不做任何额外逻辑，避免两条路径的取数口径漂移。
 * <p>
 * <b>P0-51</b>：端点覆盖生效（非生产桩/联调地址）期间，手动同步同样会拉到桩数据并写进
 * 业务表。调度器的判定是私有的，本控制器直接注入 {@code SpApiEndpointResolver} 做同一判定，
 * 而不是把桩数据当成「这家店今天没销量」。
 */
@Slf4j
@Profile("!bootstrap")
@RestController
@RequestMapping("/spapi/sales-history")
public class SalesHistoryController {

    /** 单次查询返回的最近天数上限：避免一次拉全表。 */
    private static final int MAX_QUERY_DAYS = 90;

    @Autowired
    private SalesHistorySyncService salesHistorySyncService;

    @Autowired
    private SalesHistoryMapper salesHistoryMapper;

    @Autowired
    private ShopCredentialStore credentialStore;

    /** P0-51：端点覆盖期间禁止把桩数据写入业务表。 */
    @Autowired(required = false)
    private com.amz.connector.SpApiEndpointResolver spApiEndpointResolver;

    /**
     * 手动同步本店销量历史。
     *
     * @param shopId 店铺
     * @param days   回补天数；缺省用服务默认值（30）
     * @return 同步结果（报表 ID、数据行数、写入数、行级错误）
     */
    @ShopScoped
    @RequireRole({"OPERATOR", "ADMIN"})
    @OperLog(module = "销售历史", action = "同步", description = "手动同步 Amazon 销量与流量报表")
    @PostMapping("/sync/{shopId}")
    public Result<SalesHistorySyncService.SyncReport> sync(@PathVariable Long shopId,
                                                           @RequestParam(required = false) Integer days) {
        if (shopId == null) {
            return Result.failure("shopId must not be null",
                    ErrorSummary.localError(LocalApiException.CODE_INVALID_REQUEST));
        }
        // 站点从凭证取，不接受请求参数：Amazon 报表按站点取数，
        // 允许调用方随意指定站点就能拉到别站数据，且与定时任务的口径会分叉。
        ShopCredential credential = credentialStore.get(shopId);
        String marketplaceId = credential == null ? null : credential.getMarketplaceId();
        if (marketplaceId == null || marketplaceId.isBlank()) {
            return Result.failure("店铺未登记 marketplaceId，无法按站点取报表",
                    ErrorSummary.localError(LocalApiException.CODE_INVALID_REQUEST));
        }
        if (spApiEndpointResolver != null && spApiEndpointResolver.isOverrideActive()) {
            // 与调度器同一口径：覆盖生效时拉到的是桩数据，写进业务表就会变成
            // 「这家店这几天的销量」——比不写更糟。
            return Result.failure("SP-API 端点覆盖生效中（非生产桩/联调地址），已拒绝写入业务表",
                    ErrorSummary.localError(LocalApiException.CODE_INVALID_REQUEST));
        }
        try {
            SalesHistorySyncService.SyncReport report =
                    salesHistorySyncService.sync(shopId, marketplaceId, days);
            log.info("manual sales history sync shopId={} reportId={} rows={} written={}",
                    shopId, report.getReportId(), report.getDataLineCount(), report.getWritten());
            return Result.success(report);
        } catch (Exception e) {
            // 「没拉到」不能伪装成「本期没销量」：明确失败，运营才知道要重试
            log.error("manual sales history sync failed shopId={}", shopId, e);
            return Result.failure("销量历史同步失败：" + ErrorSummary.of(e), ErrorSummary.toApiError(e));
        }
    }

    /**
     * 查询本店最近的销量历史（按日期倒序）。
     *
     * @param shopId 店铺
     * @param days   最近天数（默认 7，上限 {@value #MAX_QUERY_DAYS}）
     * @return 明细行；空数组表示「窗口内没有记录」，调用方应结合同步结果判断是
     *         未同步还是确实无销量
     */
    @ShopScoped
    @RequireRole({"VIEWER", "OPERATOR", "ADMIN"})
    @GetMapping("/list/{shopId}")
    public Result<List<SalesHistory>> list(@PathVariable Long shopId,
                                           @RequestParam(defaultValue = "7") Integer days) {
        if (shopId == null) {
            return Result.failure("shopId must not be null",
                    ErrorSummary.localError(LocalApiException.CODE_INVALID_REQUEST));
        }
        int window = days == null || days <= 0 ? 7 : Math.min(days, MAX_QUERY_DAYS);
        LocalDate from = LocalDate.now().minusDays(window);
        return Result.success(salesHistoryMapper.selectList(
                new LambdaQueryWrapper<SalesHistory>()
                        .eq(SalesHistory::getShopId, shopId)
                        .ge(SalesHistory::getSaleDate, from)
                        .orderByDesc(SalesHistory::getSaleDate)
                        .orderByAsc(SalesHistory::getSku)));
    }
}
