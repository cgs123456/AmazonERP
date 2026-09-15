package com.amz.service.impl;

import com.amz.client.SpApiFinanceClient;
import com.amz.client.dto.RemoteReportInfo;
import com.amz.dto.SettlementIngestReport;
import com.amz.mapper.SettlementDetailMapper;
import com.amz.model.SettlementDetail;
import com.amz.parse.SettlementParser;
import com.amz.parse.SettlementRow;
import com.amz.result.Result;
import com.amz.service.SettlementService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 结算原表接入实现（T04）。
 * <p>
 * 设计要点：
 * <ul>
 *   <li><b>报表轮询有上限</b>：SP-API 报表是异步的，无上限轮询会把调度线程挂死；
 *       超时按失败处理并给出明确原因，而不是静默返回空数据</li>
 *   <li><b>幂等靠业务指纹</b>：结算报表按批次下发，跨窗口重叠拉取是常态，
 *       重复行必须跳过而不是重复入账</li>
 *   <li><b>批量查重</b>：一次 SELECT ... IN 查已存在指纹，避免逐行往返数据库</li>
 *   <li><b>行级容错</b>：单行失败只记账不中断，几千行的一批不能因一行脏数据全丢</li>
 * </ul>
 */
@Slf4j
@Service
public class SettlementServiceImpl implements SettlementService {

    /** 报表轮询上限（次）。 */
    @Value("${amz.finance.settlement.max-poll-attempts:15}")
    private int maxPollAttempts = 15;

    /** 报表轮询间隔（毫秒）。 */
    @Value("${amz.finance.settlement.poll-interval-ms:2000}")
    private long pollIntervalMs = 2000L;

    /** 单批写入条数（结算报表可能上千行）。 */
    @Value("${amz.finance.settlement.batch-size:200}")
    private int batchSize = 200;

    /** 报告里保留的行级错误上限（超出仅计数并提示）。 */
    @Value("${amz.finance.settlement.max-row-errors:50}")
    private int maxRowErrors = 50;

    /** 报表类型：结算原表扁平文件。 */
    public static final String REPORT_TYPE_SETTLEMENT = "GET_V2_SETTLEMENT_REPORT_DATA_FLAT_FILE";

    @Autowired
    private SpApiFinanceClient spApiFinanceClient;

    @Autowired
    private SettlementDetailMapper settlementDetailMapper;

    @Override
    public SettlementIngestReport sync(Long shopId, String marketplaceId,
                                       String dataStartTime, String dataEndTime) {
        if (shopId == null) {
            throw new IllegalArgumentException("shopId must not be null");
        }
        SettlementIngestReport report = new SettlementIngestReport();
        report.setShopId(shopId);
        report.setReportType(REPORT_TYPE_SETTLEMENT);

        String reportId = requireSuccess(spApiFinanceClient.requestReport(
                shopId, marketplaceId, REPORT_TYPE_SETTLEMENT, dataStartTime, dataEndTime),
                "创建结算报表请求失败");
        report.setReportId(reportId);

        RemoteReportInfo info = awaitReportDone(shopId, reportId);
        report.setReportStatus(info.getProcessingStatus());

        String content = requireSuccess(spApiFinanceClient.downloadDocument(info.getDocumentId(), shopId),
                "下载结算报表文档失败");

        SettlementParser.ParseResult parsed = SettlementParser.parse(content);
        report.setDataLineCount(parsed.getDataLineCount());
        report.getRowErrors().addAll(parsed.getErrors());
        if (parsed.getErrors().size() > maxRowErrors) {
            report.setRowErrors(new ArrayList<>(parsed.getErrors().subList(0, maxRowErrors)));
            report.addWarning("行级错误已截断显示 " + maxRowErrors + " 条，实际 "
                    + parsed.getErrors().size() + " 条");
        }
        if (parsed.getDataLineCount() == 0) {
            report.addWarning("报表无数据行 —— 该时间窗口可能确实没有结算记录（非异常）");
        }

        ingestParsedRows(shopId, parsed.getRows(), report);
        log.info("settlement sync done shopId={} reportId={} dataLines={} inserted={} skipped={} failed={}",
                shopId, reportId, report.getDataLineCount(), report.getInserted(),
                report.getSkipped(), report.getFailed());
        return report;
    }

    @Override
    public void ingestParsedRows(Long shopId, List<SettlementRow> rows, SettlementIngestReport report) {
        if (rows == null || rows.isEmpty()) {
            return;
        }
        // 批内去重：同一文件内出现完全相同的行时，只入库一次
        Map<String, SettlementRow> unique = new LinkedHashMap<>();
        int withinBatchDuplicates = 0;
        for (SettlementRow row : rows) {
            row.setShopId(shopId);
            if (unique.putIfAbsent(row.getRowKey(), row) != null) {
                withinBatchDuplicates++;
            }
        }
        report.setSkipped(report.getSkipped() + withinBatchDuplicates);

        List<String> keys = new ArrayList<>(unique.keySet());
        Set<String> existing = new HashSet<>();
        for (int i = 0; i < keys.size(); i += batchSize) {
            List<String> chunk = keys.subList(i, Math.min(i + batchSize, keys.size()));
            List<String> found = settlementDetailMapper.selectExistingRowKeys(chunk);
            if (found != null) {
                existing.addAll(found);
            }
        }

        int duplicates = 0;
        int failed = 0;
        for (Map.Entry<String, SettlementRow> entry : unique.entrySet()) {
            if (existing.contains(entry.getKey())) {
                duplicates++;
                continue;
            }
            try {
                SettlementDetail detail = toDetail(entry.getValue());
                settlementDetailMapper.insert(detail);
                report.setInserted(report.getInserted() + 1);
                report.setSumAmount(report.getSumAmount()
                        .add(detail.getAmount() == null ? BigDecimal.ZERO : detail.getAmount()));
                if (detail.getCurrency() != null && !detail.getCurrency().isBlank()) {
                    report.getCurrencies().add(detail.getCurrency());
                }
            } catch (Exception e) {
                failed++;
                log.warn("settlement row insert failed shopId={} rowKey={} reason={}",
                        shopId, entry.getKey(), e.getMessage());
                if (report.getRowErrors().size() < maxRowErrors) {
                    report.getRowErrors().add(SettlementParser.rowError(0,
                            "落库失败：" + e.getMessage(), String.valueOf(entry.getKey())));
                }
            }
        }
        report.setSkipped(report.getSkipped() + duplicates);
        report.setFailed(report.getFailed() + failed);
        report.setSumAmount(report.getSumAmount().setScale(2, RoundingMode.HALF_UP));

        if (report.getCurrencies().size() > 1) {
            report.addWarning("本批涉及多币种（" + String.join(", ", report.getCurrencies())
                    + "），金额合计不可跨币种求和，请按币种分别解读");
        }
    }

    @Override
    public List<SettlementDetail> list(Long shopId, String orderId) {
        if (shopId == null) {
            throw new IllegalArgumentException("shopId must not be null");
        }
        LambdaQueryWrapper<SettlementDetail> qw = new LambdaQueryWrapper<SettlementDetail>()
                .eq(SettlementDetail::getShopId, shopId)
                .orderByDesc(SettlementDetail::getId);
        if (orderId != null && !orderId.isBlank()) {
            qw.eq(SettlementDetail::getOrderId, orderId);
        }
        return settlementDetailMapper.selectList(qw);
    }

    /**
     * 轮询报表状态直至终态。超时抛异常而非返回空 ——
     * 「报表还没好」与「本期没有结算」是两件事，静默当成后者会让利润凭空归零。
     */
    private RemoteReportInfo awaitReportDone(Long shopId, String reportId) {
        RemoteReportInfo last = null;
        for (int attempt = 1; attempt <= maxPollAttempts; attempt++) {
            last = requireSuccess(spApiFinanceClient.getReport(reportId, shopId), "查询结算报表状态失败");
            if (last == null) {
                throw new IllegalStateException("结算报表状态返回为空，reportId=" + reportId);
            }
            if (last.isDone()) {
                return last;
            }
            if (last.isTerminal()) {
                throw new IllegalStateException("结算报表处理失败，reportId=" + reportId
                        + " status=" + last.getProcessingStatus());
            }
            if (attempt < maxPollAttempts) {
                sleep(pollIntervalMs);
            }
        }
        throw new IllegalStateException("结算报表轮询超时（" + maxPollAttempts + " 次 × "
                + pollIntervalMs + "ms），reportId=" + reportId
                + " 最后状态=" + (last == null ? "?" : last.getProcessingStatus()));
    }

    /**
     * 校验 spapi 调用结果。失败即抛异常 —— 财务域不接受静默降级：
     * 空数据会被下游读成「本期没有结算」这个业务结论。
     */
    private static <T> T requireSuccess(Result<T> result, String what) {
        if (result == null) {
            throw new IllegalStateException(what + "：spapi 无响应");
        }
        if (result.getCode() != 200) {
            throw new IllegalStateException(what + "：" + result.getMessage());
        }
        return result.getData();
    }

    private static SettlementDetail toDetail(SettlementRow row) {
        SettlementDetail detail = new SettlementDetail();
        detail.setShopId(row.getShopId());
        detail.setSettlementId(row.getSettlementId());
        detail.setOrderId(row.getOrderId());
        detail.setSku(row.getSku());
        detail.setTransactionType(row.getTransactionType());
        detail.setAmountType(row.getAmountType());
        detail.setAmount(row.getAmount());
        detail.setCurrency(row.getCurrency());
        detail.setDepositDate(row.getDepositDate());
        detail.setRowKey(row.getRowKey());
        detail.setSource(SettlementDetail.SOURCE_REPORT);
        detail.setCreateTime(LocalDateTime.now());
        return detail;
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("结算报表轮询被中断");
        }
    }
}
