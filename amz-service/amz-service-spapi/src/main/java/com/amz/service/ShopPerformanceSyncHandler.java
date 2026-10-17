package com.amz.service;

import com.amz.model.ShopPerformance;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.amz.connector.ErrorSummary;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 店铺绩效原始留痕服务（P3.1）。
 * <p>
 * 拉取三段式（createReport → 轮询 → 下载）复用 ReportsClient；
 * reportType = GET_V2_SELLER_PERFORMANCE_REPORT（官方存在）。
 * 原文完整入库不做字段级解析（快照无列结构，不编造）。
 * <p>
 * <b>循环依赖说明</b>：PerformanceService 需要轮询报表，轮询实现与
 * {@code SettlementServiceImpl.awaitReportDone} 同型但独立——
 * 精简起见直接内嵌，不复用 finance 的私有实现。
 */
@Slf4j
public class ShopPerformanceSyncHandler {

    private static final DateTimeFormatter ISO_INSTANT = DateTimeFormatter.ISO_INSTANT;

    private final com.amz.client.ReportsClient reportsClient;
    private final com.amz.mapper.ShopPerformanceMapper performanceMapper;

    /** 轮询次数与挂钟上限（与结算同步同纪律）。 */
    private final int maxPollAttempts;
    private final long pollIntervalMs;
    private final long pollDeadlineMs;

    public ShopPerformanceSyncHandler(com.amz.client.ReportsClient reportsClient,
                                      com.amz.mapper.ShopPerformanceMapper performanceMapper,
                                      int maxPollAttempts, long pollIntervalMs, long pollDeadlineMs) {
        this.reportsClient = reportsClient;
        this.performanceMapper = performanceMapper;
        this.maxPollAttempts = maxPollAttempts;
        this.pollIntervalMs = pollIntervalMs;
        this.pollDeadlineMs = pollDeadlineMs;
    }

    /**
     * 拉取绩效报表并原文入库。
     *
     * @return 落库行
     */
    public ShopPerformance syncShop(Long shopId, String marketplaceId, int windowDays) {
        // §S33.34：原实现用 ISO_INSTANT 格式化 LocalDateTime，会抛
        // UnsupportedTemporalTypeException: Unsupported field: InstantSeconds ——
        // ISO_INSTANT 需要时区/瞬时信息，LocalDateTime 没有。结果是这个同步
        // **从未成功过一次**：手动端点每次都失败，而它没有任何测试。
        // 改为 OffsetDateTime（带 UTC 偏移），语义与结算同步一致。
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        String dataEnd = now.format(ISO_INSTANT);
        String dataStart = now.minusDays(Math.max(1, windowDays)).format(ISO_INSTANT);

        String reportId = reportsClient.createReport(shopId, marketplaceId,
                "GET_V2_SELLER_PERFORMANCE_REPORT", dataStart, dataEnd);

        com.amz.client.dto.ReportInfo info = null;
        long deadline = System.currentTimeMillis() + pollDeadlineMs;
        for (int attempt = 0; attempt < maxPollAttempts; attempt++) {
            info = reportsClient.getReport(shopId, reportId);
            if (info.isTerminal()) {
                break;
            }
            if (System.currentTimeMillis() > deadline) {
                break;
            }
            try {
                Thread.sleep(pollIntervalMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw com.amz.connector.LocalApiException.of(
                        com.amz.connector.LocalApiException.CODE_INTERNAL_ERROR, "轮询被中断");
            }
        }
        if (info == null || !info.isDone()) {
            throw com.amz.connector.LocalApiException.of(
                    com.amz.connector.LocalApiException.CODE_INTERNAL_ERROR,
                    "绩效报表未在限制内就绪（最后状态="
                            + (info == null ? "未知" : info.getProcessingStatus()) + "）");
        }
        if (info.getDocumentId() == null || info.getDocumentId().isBlank()) {
            throw com.amz.connector.LocalApiException.of(
                    com.amz.connector.LocalApiException.CODE_INTERNAL_ERROR,
                    "绩效报表 DONE 但缺少 documentId");
        }
        String content = reportsClient.downloadDocument(shopId, info.getDocumentId());

        ShopPerformance row = new ShopPerformance();
        row.setShopId(shopId);
        row.setMarketplaceId(marketplaceId);
        row.setReportType("GET_V2_SELLER_PERFORMANCE_REPORT");
        row.setReportId(reportId);
        row.setRawContent(content);
        row.setContentLength(content == null ? 0 : content.length());
        row.setStatus(content == null || content.isBlank() ? "EMPTY" : "SYNCED");
        row.setOperatorId(com.amz.context.UserContext.getUserId() == null
                ? null : com.amz.context.UserContext.getUserId().longValue());
        performanceMapper.insert(row);
        log.info("绩效报表留痕完成 shopId={} reportId={} length={}",
                shopId, reportId, row.getContentLength());
        return row;
    }

    /** 历史列表（最近 50 条，不含原文——原文走 detail）。 */
    public List<ShopPerformance> list(Long shopId) {
        return performanceMapper.selectList(new LambdaQueryWrapper<ShopPerformance>()
                .eq(ShopPerformance::getShopId, shopId)
                .orderByDesc(ShopPerformance::getId)
                .last("LIMIT 50"));
    }

    /** 单条详情（含原文）。 */
    public ShopPerformance detail(Long id) {
        ShopPerformance row = performanceMapper.selectById(id);
        if (row == null) {
            throw com.amz.connector.LocalApiException.of(
                    com.amz.connector.LocalApiException.CODE_INVALID_REQUEST,
                    "绩效留痕不存在：id=" + id);
        }
        return row;
    }
}
