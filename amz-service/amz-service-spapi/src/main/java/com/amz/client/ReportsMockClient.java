package com.amz.client;

import com.amz.client.dto.ReportInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

/**
 * SP-API Reports 模拟客户端。
 * <p>
 * 仅在 {@code spring.profiles.active=mock} 时生效（与 product 模块的
 * ListingsMockClient / ListingsRealClient 切换方式一致）。
 * <ul>
 *   <li>{@link #createReport} 返回 MOCK-RPT-* 递增 ID</li>
 *   <li>{@link #getReport} 恒返回 DONE（结算报表场景轮询一步到位）</li>
 *   <li>{@link #downloadDocument} 返回固定 TSV 样例（与结算原表同构，
 *       供下游解析与落库链路在无凭证环境下端到端验证）</li>
 * </ul>
 */
@Component
@Profile("mock")
public class ReportsMockClient implements ReportsClient {

    private static final Logger log = LoggerFactory.getLogger(ReportsMockClient.class);

    private final AtomicLong reportSeq = new AtomicLong();

    @Override
    public String createReport(Long shopId, String marketplaceId, String reportType,
                               String dataStartTime, String dataEndTime) {
        String reportId = "MOCK-RPT-" + reportSeq.incrementAndGet();
        log.info("createReport (mock) shopId={} reportType={} reportId={}", shopId, reportType, reportId);
        return reportId;
    }

    @Override
    public ReportInfo getReport(Long shopId, String reportId) {
        log.info("getReport (mock) shopId={} reportId={}", shopId, reportId);
        ReportInfo info = new ReportInfo();
        info.setReportId(reportId);
        info.setReportType("MOCK_SETTLEMENT_REPORT");
        info.setProcessingStatus(ReportInfo.STATUS_DONE);
        info.setDocumentId("MOCK-DOC-" + reportId);
        return info;
    }

    @Override
    public String downloadDocument(Long shopId, String documentId) {
        log.info("downloadDocument (mock) shopId={} documentId={}", shopId, documentId);
        return SAMPLE_SETTLEMENT_TSV;
    }

    /**
     * 与结算原表（GET_V2_SETTLEMENT_REPORT_DATA_FLAT_FILE）同构的 TSV 样例：
     * 一笔正常销售（收入 + 佣金 + FBA 配送费）、一笔退款、一笔 FBA 库存赔付。
     */
    static final String SAMPLE_SETTLEMENT_TSV = String.join("\n",
            "settlement-id\tsettlement-start-date\tsettlement-end-date\tdeposit-date\tcurrency\ttransaction-type\torder-id\tsku\tamount-type\tamount",
            "900001\t2026-09-01T00:00:00Z\t2026-09-07T23:59:59Z\t2026-09-08T00:00:00Z\tUSD\tOrder\t111-0000001-0000001\tSKU-ALPHA\tPrincipal\t29.99",
            "900001\t2026-09-01T00:00:00Z\t2026-09-07T23:59:59Z\t2026-09-08T00:00:00Z\tUSD\tOrder\t111-0000001-0000001\tSKU-ALPHA\tCommission\t-4.50",
            "900001\t2026-09-01T00:00:00Z\t2026-09-07T23:59:59Z\t2026-09-08T00:00:00Z\tUSD\tOrder\t111-0000001-0000001\tSKU-ALPHA\tFBAPerUnitFulfillmentFee\t-5.03",
            "900001\t2026-09-01T00:00:00Z\t2026-09-07T23:59:59Z\t2026-09-08T00:00:00Z\tUSD\tRefund\t111-0000002-0000002\tSKU-BETA\tPrincipal\t-49.99",
            "900001\t2026-09-01T00:00:00Z\t2026-09-07T23:59:59Z\t2026-09-08T00:00:00Z\tUSD\tAdjustment\t\tSKU-GAMMA\tFBA Inventory Reimbursement\t12.50",
            "");
}
