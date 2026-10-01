package com.amz.service.impl;

import com.amz.batch.BatchInserts;
import com.amz.client.SpApiFinanceClient;
import com.amz.client.dto.RemoteReportInfo;
import com.amz.dto.SettlementIngestReport;
import com.amz.mapper.SettlementDetailMapper;
import com.amz.model.SettlementDetail;
import com.amz.parse.SettlementParser;
import com.amz.parse.SettlementRow;
import com.amz.result.PageRequest;
import com.amz.result.PageResult;
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

    /**
     * 报表轮询的<b>挂钟</b>上限（毫秒）。
     * <p>
     * 只有次数上限是不够的：每次 {@code getReport} 都可能耗到 spapi 读超时（10s），
     * 15 次的实际等待是 28s 睡眠 + 最慢 150s 网络 ≈ 3 分钟，而网关 30s 就丢弃响应了。
     * 结果是调用方早已看到超时、用户重点一次按钮，服务端却还压着一个 Tomcat 工作线程在轮询。
     * 挂钟上限把这件事划上句号；比次数上限更贴近真实风险。
     */
    @Value("${amz.finance.settlement.poll-deadline-ms:90000}")
    private long pollDeadlineMs = 90_000L;

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
        List<SettlementDetail> pending = new ArrayList<>(unique.size());
        for (Map.Entry<String, SettlementRow> entry : unique.entrySet()) {
            if (existing.contains(entry.getKey())) {
                duplicates++;
                continue;
            }
            pending.add(toDetail(entry.getValue()));
        }

        // 分块批量写 + 失败块退回逐条（保留"哪几行没进去"的归属）
        BatchInserts.Result batched = BatchInserts.saveChunks(
                pending, batchSize,
                block -> writeBatch(shopId, report, block),
                detail -> writeOne(shopId, report, detail),
                SettlementDetail::getRowKey, 0);

        report.setSkipped(report.getSkipped() + duplicates + batched.getSkipped());
        report.setFailed(report.getFailed() + batched.getFailed());
        report.setSumAmount(report.getSumAmount().setScale(2, RoundingMode.HALF_UP));
        if (batched.getFallbackChunks() > 0) {
            log.warn("结算落库有 {} 个块从批量退回到逐条：shopId={} 块大小={}",
                    batched.getFallbackChunks(), shopId, batchSize);
        }

        if (report.getCurrencies().size() > 1) {
            report.addWarning("本批涉及多币种（" + String.join(", ", report.getCurrencies())
                    + "），金额合计不可跨币种求和，请按币种分别解读");
        }
    }

    /**
     * 真正的批量写入出口。单独开一个可替换的缝：
     * 单测里没有 Spring 上下文，{@code Db.saveBatch} 拿不到 SqlSessionFactory，
     * 不给替换点就只能测到"退回逐条"这条兜底路径，批量那条路永远是黑的。
     */
    java.util.function.Consumer<java.util.Collection<SettlementDetail>> batchWriter =
            com.baomidou.mybatisplus.extension.toolkit.Db::saveBatch;

    /** 整块批量写成功后才累计金额/币种，避免与逐条重试重复计。 */
    private void writeBatch(Long shopId, SettlementIngestReport report, List<SettlementDetail> block) {
        batchWriter.accept(block);
        for (SettlementDetail detail : block) {
            report.setInserted(report.getInserted() + 1);
            accumulate(report, detail);
        }
    }

    /**
     * 逐条兜底。三种结果：写入成功 / 唯一键冲突（说明批量那一半已经把它写进去了，算跳过且仍要累计）
     * / 其它异常（计入失败并保留 rowKey 归属）。
     */
    private BatchInserts.RowOutcome writeOne(Long shopId, SettlementIngestReport report, SettlementDetail detail) {
        try {
            settlementDetailMapper.insert(detail);
            report.setInserted(report.getInserted() + 1);
            accumulate(report, detail);
            return BatchInserts.RowOutcome.INSERTED;
        } catch (Exception e) {
            if (isDuplicateKey(e)) {
                // 批量执行器可能已把块内前几行提交掉（autocommit 形状），重试必然撞唯一键
                accumulate(report, detail);
                return BatchInserts.RowOutcome.SKIPPED;
            }
            log.warn("settlement row insert failed shopId={} rowKey={} reason={}",
                    shopId, detail.getRowKey(), e.getMessage());
            if (report.getRowErrors().size() < maxRowErrors) {
                report.getRowErrors().add(SettlementParser.rowError(0,
                        "落库失败：" + e.getMessage(), String.valueOf(detail.getRowKey())));
            }
            return BatchInserts.RowOutcome.FAILED;
        }
    }

    private static void accumulate(SettlementIngestReport report, SettlementDetail detail) {
        report.setSumAmount(report.getSumAmount()
                .add(detail.getAmount() == null ? BigDecimal.ZERO : detail.getAmount()));
        if (detail.getCurrency() != null && !detail.getCurrency().isBlank()) {
            report.getCurrencies().add(detail.getCurrency());
        }
    }

    /** 唯一键冲突的识别要看整条因果链：驱动抛的类型会被 MyBatis/Spring 逐层包装。 */
    private static boolean isDuplicateKey(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof java.sql.SQLIntegrityConstraintViolationException) {
                return true;
            }
            String message = t.getMessage();
            if (message != null && message.contains("Duplicate entry")) {
                return true;
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return false;
    }

    @Override
    public PageResult<SettlementDetail> list(Long shopId, String amazonOrderId, PageRequest page) {
        if (shopId == null) {
            throw new IllegalArgumentException("shopId must not be null");
        }
        PageRequest req = page == null ? PageRequest.first(PageRequest.DEFAULT_SIZE) : page;
        LambdaQueryWrapper<SettlementDetail> qw = new LambdaQueryWrapper<SettlementDetail>()
                .eq(SettlementDetail::getShopId, shopId);
        if (amazonOrderId != null && !amazonOrderId.isBlank()) {
            qw.eq(SettlementDetail::getAmazonOrderId, amazonOrderId);
        }
        Long cursorId = req.cursorId();
        if (cursorId != null) {
            qw.lt(SettlementDetail::getId, cursorId);
        }
        qw.orderByDesc(SettlementDetail::getId)
                .last("LIMIT " + req.probeSize());
        List<SettlementDetail> rows = settlementDetailMapper.selectList(qw);
        if (rows.size() > req.size()) {
            log.warn("结算明细列表被截断：shopId={} size={}，调用方需携带 nextCursor 继续翻页",
                    shopId, req.size());
        }
        return PageResult.of(rows, req.size(), row -> PageRequest.encodeCursor(row.getId()));
    }

    /**
     * 轮询报表状态直至终态。超时抛异常而非返回空 ——
     * 「报表还没好」与「本期没有结算」是两件事，静默当成后者会让利润凭空归零。
     * <p>
     * 双重上限：次数（避免无限轮）+ 挂钟（避免网络慢时把等待拉长到调用方早已放弃之后）。
     */
    private RemoteReportInfo awaitReportDone(Long shopId, String reportId) {
        long startedAt = nanoTime();
        RemoteReportInfo last = null;
        int attempts = 0;
        for (int attempt = 1; attempt <= maxPollAttempts; attempt++) {
            attempts = attempt;
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
                long remainingMs = pollDeadlineMs - elapsedMs(startedAt);
                if (remainingMs <= 0) {
                    break;
                }
                // 最后一次等待不越过挂钟上限
                sleep(Math.min(pollIntervalMs, remainingMs));
            }
        }
        throw new IllegalStateException("结算报表轮询超时（上限 " + maxPollAttempts + " 次 / "
                + pollDeadlineMs + "ms，实际 " + attempts + " 次 / " + elapsedMs(startedAt) + "ms），reportId="
                + reportId + " 最后状态=" + (last == null ? "?" : last.getProcessingStatus()));
    }

    /**
     * 挂钟取样。单独开一个可覆写的缝，是为了让"挂钟到点就停"这条规则能被测试真正跑到，
     * 而不是必须真的等 90 秒。
     */
    protected long nanoTime() {
        return System.nanoTime();
    }

    private long elapsedMs(long startedAtNanos) {
        return (nanoTime() - startedAtNanos) / 1_000_000L;
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
        detail.setAmazonOrderId(row.getAmazonOrderId());
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
