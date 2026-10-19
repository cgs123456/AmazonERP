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

    /**
     * reportId → reportType。下载文档时必须知道这是哪一类报表。
     * <p>
     * 旧实现无论 {@code reportType} 是什么都返回结算 TSV。销量历史同步接上后，
     * 这个「一律返回结算样例」会让 mock/离线环境把结算表的列当成销量列解析，
     * 结果是解析器整批拒绝（列缺失）——看起来像同步坏了，实则是 mock 在撒谎。
     * <p>
     * 这个 Map 无界，但只存在于 {@code mock} profile 的单进程内，且 mock 仅用于离线演示
     * 与测试；不为了省几 KB 引入淘汰逻辑。
     */
    private final java.util.concurrent.ConcurrentHashMap<String, String> reportTypesByReportId =
            new java.util.concurrent.ConcurrentHashMap<>();

    @Override
    public String createReport(Long shopId, String marketplaceId, String reportType,
                               String dataStartTime, String dataEndTime) {
        String reportId = "MOCK-RPT-" + reportSeq.incrementAndGet();
        reportTypesByReportId.put(reportId, reportType);
        log.info("createReport (mock) shopId={} reportType={} reportId={}", shopId, reportType, reportId);
        return reportId;
    }

    @Override
    public ReportInfo getReport(Long shopId, String reportId) {
        log.info("getReport (mock) shopId={} reportId={}", shopId, reportId);
        ReportInfo info = new ReportInfo();
        info.setReportId(reportId);
        info.setReportType(reportTypesByReportId.getOrDefault(reportId, "MOCK_UNKNOWN_REPORT"));
        info.setProcessingStatus(ReportInfo.STATUS_DONE);
        info.setDocumentId("MOCK-DOC-" + reportId);
        return info;
    }

    @Override
    public String downloadDocument(Long shopId, String documentId) {
        log.info("downloadDocument (mock) shopId={} documentId={}", shopId, documentId);
        // documentId 形如 MOCK-DOC-MOCK-RPT-3，据此还原 reportId 再查报表类型
        String reportId = documentId == null ? null : documentId.replaceFirst("^MOCK-DOC-", "");
        String reportType = reportId == null ? null : reportTypesByReportId.get(reportId);
        if (SALES_TRAFFIC_REPORT_TYPE.equals(reportType)) {
            return SAMPLE_SALES_TRAFFIC_TSV;
        }
        if (reportType != null && !SETTLEMENT_REPORT_TYPE.equals(reportType)) {
            // 其他报表类型没有样例：返回结算表会被下游当成本类型解析，静默产生错误数据。
            // fail-closed 抛错，与真实客户端「拿不到就失败」一致。
            throw new IllegalStateException(
                    "mock has no sample document for reportType=" + reportType
                            + "（已知：" + SETTLEMENT_REPORT_TYPE + " / " + SALES_TRAFFIC_REPORT_TYPE + "）");
        }
        return SAMPLE_SETTLEMENT_TSV;
    }

    /** 结算原表。 */
    static final String SETTLEMENT_REPORT_TYPE = "GET_V2_SETTLEMENT_REPORT_DATA_FLAT_FILE";

    /** 销量与流量报表。 */
    static final String SALES_TRAFFIC_REPORT_TYPE = "GET_SALES_AND_TRAFFIC_REPORT";

    /**
     * 与 {@code GET_SALES_AND_TRAFFIC_REPORT} 同构的样例。
     * <p>
     * 列名必须与 {@code SalesTrafficParser} 的必需列一致（小写 {@code startdate} /
     * {@code sku}），并覆盖三种真实形态：同日同 SKU 多行（按 asin 拆）、
     * 0 销量行、汇总行（无 SKU，应被解析器跳过）。
     */
    static final String SAMPLE_SALES_TRAFFIC_TSV = String.join("\n",
            "(parent)asin\tchildasin\tstartdate\tenddate\tsku\tunitsordered\torderedproductsales",
            "B0SYN00001\tB0SYN00001\t2026-10-01\t2026-10-01\tSYN-ELE-0101\t12\t359.88",
            "B0SYN00001\tB0SYN00002\t2026-10-01\t2026-10-01\tSYN-ELE-0101\t3\t89.97",
            "B0SYN00003\tB0SYN00003\t2026-10-01\t2026-10-01\tSYN-HOM-0103\t0\t0.00",
            "B0SYN00004\tB0SYN00004\t2026-10-02\t2026-10-02\tSYN-ELE-0102\t7\t209.93",
            "\t\t2026-10-02\t2026-10-02\t\t\t",
            "");

    /**
     * 与结算原表（GET_V2_SETTLEMENT_REPORT_DATA_FLAT_FILE）同构的 TSV 样例：
     * 一笔正常销售（收入 + 佣金 + FBA 配送费）、一笔退款、一笔 FBA 库存赔付。
     */
    static final String SAMPLE_SETTLEMENT_TSV = String.join("\n",
            "settlement-id\tsettlement-start-date\tsettlement-end-date\tdeposit-date\tcurrency\ttransaction-type\torder-id\tsku\tamount-type\tamount",
            "900001\t2026-09-01T00:00:00Z\t2026-09-07T23:59:59Z\t2026-09-08T00:00:00Z\tUSD\tOrder\t111-1700000001-0000001\tSYN-ELE-0101\tPrincipal\t29.99",
            "900001\t2026-09-01T00:00:00Z\t2026-09-07T23:59:59Z\t2026-09-08T00:00:00Z\tUSD\tOrder\t111-1700000001-0000001\tSYN-ELE-0101\tCommission\t-4.50",
            "900001\t2026-09-01T00:00:00Z\t2026-09-07T23:59:59Z\t2026-09-08T00:00:00Z\tUSD\tOrder\t111-1700000001-0000001\tSYN-ELE-0101\tFBAPerUnitFulfillmentFee\t-5.03",
            "900001\t2026-09-01T00:00:00Z\t2026-09-07T23:59:59Z\t2026-09-08T00:00:00Z\tUSD\tRefund\t111-1700000002-0000002\tSYN-ELE-0102\tPrincipal\t-49.99",
            "900001\t2026-09-01T00:00:00Z\t2026-09-07T23:59:59Z\t2026-09-08T00:00:00Z\tUSD\tAdjustment\t\tSYN-HOM-0103\tFBA Inventory Reimbursement\t12.50",
            "");
}
