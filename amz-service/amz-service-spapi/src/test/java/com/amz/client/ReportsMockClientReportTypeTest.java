package com.amz.client;

import com.amz.client.dto.ReportInfo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * mock 报表客户端按 reportType 分发文档。
 * <p>
 * <b>为什么需要</b>：旧实现无论 reportType 是什么都返回结算 TSV。在销量历史同步
 * 接入之前这没有后果（只有结算一个消费者）；接入之后，mock/离线环境会把结算列
 * 当成销量列解析，解析器因缺 {@code startdate}/{@code sku} 整批拒绝——
 * 表现成「同步坏了」，实则是 mock 在撒谎。更危险的是反过来：如果某天结算表的列
 * 恰好包含销量解析器要的列名，就会产生**看起来成功、数值全错**的数据。
 * <p>
 * 同时钉住「未知报表类型 fail-closed」：宁可报错，也不返回一份内容不对的文档。
 */
@DisplayName("mock 报表客户端：文档内容随 reportType 变化")
class ReportsMockClientReportTypeTest {

    private static final Long SHOP_ID = 1L;
    private static final String MARKETPLACE_ID = "ATVPDKIKX0DER";

    @Test
    @DisplayName("销量报表返回可通过 SalesTrafficParser 必需列校验的 TSV")
    void salesTrafficReportReturnsCompatibleDocument() {
        ReportsMockClient client = new ReportsMockClient();
        String reportId = client.createReport(SHOP_ID, MARKETPLACE_ID,
                ReportsMockClient.SALES_TRAFFIC_REPORT_TYPE, "2026-10-01T00:00:00Z", "2026-10-19T00:00:00Z");

        // reportType 必须被记住：getReport 回读时不能退化成固定的 MOCK_SETTLEMENT_REPORT
        ReportInfo info = client.getReport(SHOP_ID, reportId);
        assertEquals(ReportsMockClient.SALES_TRAFFIC_REPORT_TYPE, info.getReportType());

        String doc = client.downloadDocument(SHOP_ID, info.getDocumentId());
        List<String> lines = doc.lines().filter(l -> !l.isBlank()).toList();
        assertTrue(lines.size() >= 2, "至少 1 表头 + 1 数据行");
        String header = lines.get(0).toLowerCase();
        assertTrue(header.contains("startdate"), "表头必须含 startdate：" + lines.get(0));
        assertTrue(header.contains("sku"), "表头必须含 sku：" + lines.get(0));
        assertTrue(header.contains("unitsordered"), "表头必须含 unitsordered");
        assertTrue(header.contains("orderedproductsales"), "表头必须含 orderedproductsales");
        // 每行列数一致，避免下游按列号取值错位
        int columns = lines.get(0).split("\t", -1).length;
        for (String line : lines) {
            assertEquals(columns, line.split("\t", -1).length, "列数不一致：" + line);
        }
    }

    @Test
    @DisplayName("结算报表仍然返回结算 TSV（不能因为新增分发而改坏既有消费者）")
    void settlementReportStillReturnsSettlementDocument() {
        ReportsMockClient client = new ReportsMockClient();
        String reportId = client.createReport(SHOP_ID, MARKETPLACE_ID,
                ReportsMockClient.SETTLEMENT_REPORT_TYPE, "2026-09-01T00:00:00Z", "2026-09-07T23:59:59Z");
        String doc = client.downloadDocument(SHOP_ID, client.getReport(SHOP_ID, reportId).getDocumentId());

        assertEquals(ReportsMockClient.SAMPLE_SETTLEMENT_TSV, doc);
    }

    @Test
    @DisplayName("未知报表类型 fail-closed，不返回内容不对的文档")
    void unknownReportTypeFailsClosed() {
        ReportsMockClient client = new ReportsMockClient();
        String reportId = client.createReport(SHOP_ID, MARKETPLACE_ID,
                "GET_SOMETHING_WE_HAVE_NO_SAMPLE_FOR", null, null);

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> client.downloadDocument(SHOP_ID, "MOCK-DOC-" + reportId));
        assertTrue(e.getMessage().contains("GET_SOMETHING_WE_HAVE_NO_SAMPLE_FOR"), e.getMessage());
    }
}
