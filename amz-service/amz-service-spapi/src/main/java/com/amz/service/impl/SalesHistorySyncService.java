package com.amz.service.impl;

import com.amz.client.ReportsClient;
import com.amz.client.dto.ReportInfo;
import com.amz.client.parse.SalesTrafficParser;
import com.amz.client.parse.SalesTrafficParser.ParseResult;
import com.amz.mapper.SalesHistoryMapper;
import com.amz.model.SalesHistory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code amz_sales_history} 的写入方——此前全仓唯一缺的环节。
 * <p>
 * <b>为什么必须有它</b>：{@code ReplenishmentEngine} 读这张表算基线日均需求
 * （7 天 0.7 权重 + 30 天 0.3 权重）。写入方缺失的后果不是「没有建议」，
 * 而是**建议一直在出、但基于空历史**——页面看起来正常工作，实则空转。
 * V11 迁移注释里已记账「这张表从未被写入过」，本节把它补上。
 * <p>
 * <b>与结算同步的同构</b>：三步式（createReport → 轮询 → downloadDocument），
 * 沿用 {@code SettlementServiceImpl} 已验证的形状，包括挂钟上限。
 * <p>
 * <b>刻意不做</b>：
 * <ul>
 *   <li>不做增量水位。报表按时间窗整体给出当日全量，upsert 天然幂等；
 *      存「上次同步到哪」反而要在别处维护状态，多一处会漂移的东西。</li>
 *   <li>不按 SKU 拆多次请求。一次拉全店报表再由解析器分组，比每 SKU 一次调用
 *      省掉 N-1 次 API 往返。</li>
 * </ul>
 */
@Slf4j
@Service
public class SalesHistorySyncService {

    /** 销量与流量报表：按 (date × asin × sku × marketplace) 给明细。 */
    public static final String REPORT_TYPE_SALES_TRAFFIC = "GET_SALES_AND_TRAFFIC_REPORT";

    @Autowired
    private ReportsClient reportsClient;

    @Autowired
    private SalesHistoryMapper salesHistoryMapper;

    /** 报表轮询间隔（毫秒）。 */
    @Value("${spapi.sales-history.poll-interval-ms:2000}")
    private long pollIntervalMs = 2000L;

    /**
     * 报表轮询的<b>挂钟</b>上限（毫秒）。
     * <p>
     * 与结算同步同一条理由：每次 getReport 可能耗到读超时，只有次数上限时
     * 实际等待 = 次数 × (睡眠 + 最慢网络)，网关早已丢弃响应而服务端还在轮询。
     */
    @Value("${spapi.sales-history.poll-deadline-ms:90000}")
    private long pollDeadlineMs = 90_000L;

    /** 默认回补窗口（天）。30 天是 ReplenishmentEngine 的基线窗口，取同值。 */
    @Value("${spapi.sales-history.default-days:30}")
    private int defaultDays = 30;

    /** 单批写入条数。 */
    @Value("${spapi.sales-history.batch-size:500}")
    private int batchSize = 500;

    /**
     * 同步单店销量历史。
     *
     * @param shopId       店铺 ID（必填）
     * @param marketplaceId 商城 ID（必填）
     * @param days          回补天数；null 用默认 30
     * @return 同步结果：报表 ID、行数、写入数、行级错误
     */
    public SyncReport sync(Long shopId, String marketplaceId, Integer days) {
        if (shopId == null) {
            throw new IllegalArgumentException("shopId must not be null");
        }
        SyncReport report = new SyncReport(shopId, REPORT_TYPE_SALES_TRAFFIC);
        int window = days == null || days <= 0 ? defaultDays : days;

        OffsetDateTime end = OffsetDateTime.now(ZoneOffset.UTC);
        OffsetDateTime start = end.minusDays(window);
        String dataStartTime = start.toString();
        String dataEndTime = end.toString();

        String reportId = reportsClient.createReport(
                shopId, marketplaceId, REPORT_TYPE_SALES_TRAFFIC, dataStartTime, dataEndTime);
        report.setReportId(reportId);

        ReportInfo info = awaitReportDone(shopId, reportId);
        report.setReportStatus(info.getProcessingStatus());

        String content = reportsClient.downloadDocument(shopId, info.getDocumentId());

        ParseResult parsed = SalesTrafficParser.parse(decode(content));
        report.setDataLineCount(parsed.getRows().size());
        report.setSkippedLines(parsed.getSkippedLines());
        report.getRowErrors().addAll(truncateErrors(parsed.getErrors()));

        ingest(shopId, parsed.getRows(), report);

        log.info("sales history sync done shopId={} reportId={} days={} rows={} written={} errors={}",
                shopId, reportId, window, parsed.getRows().size(), report.getWritten(),
                parsed.getErrors().size());
        return report;
    }

    /**
     * 落库。抽成包可见是为了测试能直接调用——同步链路要真实凭据，
     * 而「解析结果 → upsert」这一段不该被那个门槛挡住。
     */
    void ingest(Long shopId, List<SalesTrafficParser.SalesRow> rows, SyncReport report) {
        // 店铺为空是整个链路里最危险的形状：行数据本身不带店铺，
        // 一旦这里放过，下面的 upsert 会按 sku+date 撞键后覆盖别家店的行。
        if (shopId == null) {
            throw new IllegalArgumentException("shopId must not be null");
        }
        if (rows == null || rows.isEmpty()) {
            report.addWarning("报表无数据行 —— 该时间窗口可能确实没有销量（非异常）");
            return;
        }
        List<SalesHistory> entities = new ArrayList<>(rows.size());
        for (SalesTrafficParser.SalesRow row : rows) {
            SalesHistory entity = new SalesHistory();
            entity.setShopId(shopId);
            entity.setSku(row.sku());
            entity.setSaleDate(row.saleDate());
            entity.setQuantity(row.units());
            entity.setRevenue(row.revenue() == null ? BigDecimal.ZERO : row.revenue());
            entities.add(entity);
        }

        int written = 0;
        for (int i = 0; i < entities.size(); i += batchSize) {
            List<SalesHistory> chunk = entities.subList(i, Math.min(i + batchSize, entities.size()));
            // upsert 返回「插入 + 更新」的行数，重复同步时也是正整数；
            // 返回 <=0 说明整块没写进去，那必须让人知道，不能当成「本来就是这些值」。
            int affected = salesHistoryMapper.upsertBatch(chunk);
            if (affected <= 0) {
                log.warn("销量历史 upsert 未写入任何行：shopId={} chunkIndex={} size={}",
                        shopId, i, chunk.size());
                report.addWarning("有一批销量数据未写入（chunk " + i + "，" + chunk.size() + " 行），请重试");
                continue;
            }
            written += chunk.size();
        }
        report.setWritten(report.getWritten() + written);
    }

    /** 文档内容解码：报表可能是 GZIP 压缩的 TSV。 */
    private String decode(String content) {
        // ReportsClient.downloadDocument 已返回解码后的文本；
        // 这里保留一层是为了将来接 ReportDocumentDecoder 时不改调用点。
        return content == null ? "" : content;
    }

    private ReportInfo awaitReportDone(Long shopId, String reportId) {
        long deadline = System.currentTimeMillis() + pollDeadlineMs;
        while (true) {
            ReportInfo info = reportsClient.getReport(shopId, reportId);
            String processingStatus = info == null ? null : info.getProcessingStatus();
            String documentId = info == null ? null : info.getDocumentId();

            if (ReportInfo.STATUS_DONE.equalsIgnoreCase(processingStatus)) {
                if (documentId == null || documentId.isBlank()) {
                    throw new IllegalStateException("销量报表已 DONE 但无 reportDocumentId：reportId=" + reportId);
                }
                return info;
            }
            if (ReportInfo.STATUS_FATAL.equalsIgnoreCase(processingStatus)
                    || ReportInfo.STATUS_CANCELLED.equalsIgnoreCase(processingStatus)) {
                // 报表失败不等于「没有销量」：必须响亮失败，否则上游会基于陈旧数据出建议
                throw new IllegalStateException("销量报表生成失败：reportId=" + reportId + " status=" + processingStatus);
            }
            if (System.currentTimeMillis() > deadline) {
                throw new IllegalStateException(
                        "等待销量报表超时（" + pollDeadlineMs + "ms）：reportId=" + reportId
                                + " status=" + processingStatus);
            }
            sleep(pollIntervalMs);
        }
    }

    private List<com.amz.client.parse.SalesTrafficParser.RowError> truncateErrors(
            List<com.amz.client.parse.SalesTrafficParser.RowError> errors) {
        if (errors.size() <= 50) {
            return errors;
        }
        List<com.amz.client.parse.SalesTrafficParser.RowError> out = new ArrayList<>(errors.subList(0, 50));
        out.add(new com.amz.client.parse.SalesTrafficParser.RowError(
                -1, "行级错误已截断显示 50 条，实际 " + errors.size() + " 条", ""));
        return out;
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待销量报表被中断", e);
        }
    }

    /**
     * 同步结果。字段与 {@code SettlementIngestReport} 对齐，便于同一套前端呈现。
     */
    public static final class SyncReport {
        private final Long shopId;
        private final String reportType;
        private String reportId;
        private String reportStatus;
        private int dataLineCount;
        private int skippedLines;
        private int written;
        private final List<String> warnings = new ArrayList<>();
        private final List<com.amz.client.parse.SalesTrafficParser.RowError> rowErrors = new ArrayList<>();

        SyncReport(Long shopId, String reportType) {
            this.shopId = shopId;
            this.reportType = reportType;
        }

        public Long getShopId() {
            return shopId;
        }

        public String getReportType() {
            return reportType;
        }

        public String getReportId() {
            return reportId;
        }

        void setReportId(String reportId) {
            this.reportId = reportId;
        }

        public String getReportStatus() {
            return reportStatus;
        }

        void setReportStatus(String reportStatus) {
            this.reportStatus = reportStatus;
        }

        public int getDataLineCount() {
            return dataLineCount;
        }

        void setDataLineCount(int dataLineCount) {
            this.dataLineCount = dataLineCount;
        }

        public int getSkippedLines() {
            return skippedLines;
        }

        void setSkippedLines(int skippedLines) {
            this.skippedLines = skippedLines;
        }

        public int getWritten() {
            return written;
        }

        void setWritten(int written) {
            this.written = written;
        }

        public List<String> getWarnings() {
            return warnings;
        }

        void addWarning(String warning) {
            this.warnings.add(warning);
        }

        public List<com.amz.client.parse.SalesTrafficParser.RowError> getRowErrors() {
            return rowErrors;
        }
    }
}
