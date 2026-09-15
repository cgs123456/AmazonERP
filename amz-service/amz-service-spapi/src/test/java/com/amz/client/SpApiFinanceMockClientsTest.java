package com.amz.client;

import com.amz.client.dto.FeeEstimate;
import com.amz.client.dto.FinancialEvent;
import com.amz.client.dto.ReportInfo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 财务域三个模拟客户端的单元测试（Reports / Finances / Fees）。
 * <p>
 * 覆盖：报表三段式（创建 → 状态 → 下载）链路形态、结算事件四类构成与时间窗口过滤、
 * 费用预估的计价确定性与参数校验。
 * <p>
 * 这些断言同时充当「mock 数据契约」：下游 finance 模块的解析与聚合测试依赖同一批样例数据，
 * 修改样例即会在这里失败，避免 mock 数据被无意改坏后下游静默失真。
 */
@DisplayName("SP-API 财务域模拟客户端测试")
class SpApiFinanceMockClientsTest {

    private static final Long SHOP_ID = 1L;
    private static final String MARKETPLACE_ID = "ATVPDKIKX0DER";

    @Test
    @DisplayName("Reports：创建报表返回递增 ID，状态恒 DONE 且携带 documentId")
    void reportsMockFlow() {
        ReportsMockClient client = new ReportsMockClient();
        String first = client.createReport(SHOP_ID, MARKETPLACE_ID,
                "GET_V2_SETTLEMENT_REPORT_DATA_FLAT_FILE", "2026-09-01T00:00:00Z", "2026-09-07T23:59:59Z");
        String second = client.createReport(SHOP_ID, MARKETPLACE_ID,
                "GET_V2_SETTLEMENT_REPORT_DATA_FLAT_FILE", "2026-09-08T00:00:00Z", "2026-09-14T23:59:59Z");

        assertTrue(first.startsWith("MOCK-RPT-"), "reportId should carry MOCK-RPT- prefix: " + first);
        assertNotEquals(first, second, "sequential createReport must return distinct report ids");

        ReportInfo info = client.getReport(SHOP_ID, first);
        assertEquals(ReportInfo.STATUS_DONE, info.getProcessingStatus());
        assertTrue(info.isDone());
        assertTrue(info.isTerminal());
        assertEquals("MOCK-DOC-" + first, info.getDocumentId());
    }

    @Test
    @DisplayName("Reports：下载文档返回同构结算 TSV（1 表头 + 5 数据行）")
    void reportsMockDocumentShape() {
        ReportsMockClient client = new ReportsMockClient();
        String doc = client.downloadDocument(SHOP_ID, "MOCK-DOC-MOCK-RPT-1");

        List<String> lines = doc.lines().filter(l -> !l.isBlank()).collect(Collectors.toList());
        assertEquals(6, lines.size(), "expect 1 header + 5 data rows");
        assertTrue(lines.get(0).startsWith("settlement-id\t"), "header must be TSV with settlement-id first");
        assertTrue(doc.contains("111-0000001-0000001"), "should contain the normal sale order");
        assertTrue(doc.contains("Refund"), "should contain a refund row");
        assertTrue(doc.contains("FBA Inventory Reimbursement"), "should contain a reimbursement row");
        // 每行字段数一致（10 列），避免下游按列号取值时错位
        for (String line : lines) {
            assertEquals(10, line.split("\t", -1).length, "column count mismatch: " + line);
        }
    }

    @Test
    @DisplayName("Finances：不筛选时返回 7 条事件，四类齐全且净额守恒")
    void financesMockAllEvents() {
        FinancesMockClient client = new FinancesMockClient();
        List<FinancialEvent> events = client.listFinancialEvents(SHOP_ID, null, null);

        assertEquals(7, events.size());
        Map<String, Long> byType = events.stream()
                .collect(Collectors.groupingBy(FinancialEvent::getType, Collectors.counting()));
        assertEquals(2L, byType.get(FinancialEvent.TYPE_INCOME));
        assertEquals(3L, byType.get(FinancialEvent.TYPE_FEE));
        assertEquals(1L, byType.get(FinancialEvent.TYPE_REFUND));
        assertEquals(1L, byType.get(FinancialEvent.TYPE_ADJUSTMENT));

        BigDecimal net = events.stream()
                .map(FinancialEvent::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(new BigDecimal("25.46"), net, "有符号金额直接求和应等于净影响");
        events.forEach(e -> assertTrue(e.getEventId() != null && !e.getEventId().isBlank(),
                "every mock event must carry a deterministic eventId for idempotent persistence"));
    }

    @Test
    @DisplayName("Finances：时间窗口过滤按 ISO 字典序（下界含、上界含）")
    void financesMockWindowFilter() {
        FinancesMockClient client = new FinancesMockClient();

        List<FinancialEvent> after = client.listFinancialEvents(SHOP_ID, "2026-09-04T00:00:00Z", null);
        assertEquals(2, after.size(), "window after 09-04 should keep refund + adjustment");
        assertTrue(after.stream().allMatch(e -> e.getPostedAt().compareTo("2026-09-04T00:00:00Z") >= 0));

        List<FinancialEvent> before = client.listFinancialEvents(SHOP_ID, null, "2026-09-02T23:59:59Z");
        assertEquals(3, before.size(), "window before 09-02 end should keep only order one");

        List<FinancialEvent> exact = client.listFinancialEvents(SHOP_ID,
                "2026-09-02T12:00:00Z", "2026-09-02T12:00:00Z");
        assertEquals(3, exact.size(), "边界值应被包含（含上下界）");
    }

    @Test
    @DisplayName("Fees：15% 佣金 + 价格分档配送费，总额与净得自洽")
    void feesMockPricing() {
        FeesMockClient client = new FeesMockClient();

        FeeEstimate high = client.estimateFbaFees(SHOP_ID, MARKETPLACE_ID, "B0ALPHA", "SKU-ALPHA",
                new BigDecimal("29.99"), "USD");
        assertEquals(new BigDecimal("4.50"), high.getReferralFee());
        assertEquals(new BigDecimal("5.87"), high.getFulfillmentFee());
        assertEquals(BigDecimal.ZERO, high.getOtherFees());
        assertEquals(new BigDecimal("10.37"), high.getTotalFees());
        assertEquals(new BigDecimal("19.62"), high.getEstimatedNet());
        assertEquals(new BigDecimal("29.99").subtract(high.getTotalFees()), high.getEstimatedNet());

        FeeEstimate low = client.estimateFbaFees(SHOP_ID, MARKETPLACE_ID, "B0CHEAP", "SKU-CHEAP",
                new BigDecimal("8.00"), "USD");
        assertEquals(new BigDecimal("1.20"), low.getReferralFee());
        assertEquals(new BigDecimal("3.31"), low.getFulfillmentFee());
        assertEquals(new BigDecimal("4.51"), low.getTotalFees());
        assertEquals(new BigDecimal("3.49"), low.getEstimatedNet());

        FeeEstimate mid = client.estimateFbaFees(SHOP_ID, MARKETPLACE_ID, "B0MID", null,
                new BigDecimal("15.00"), "USD");
        assertEquals(new BigDecimal("4.68"), mid.getFulfillmentFee(), "≤20 档应为 4.68");
    }

    @Test
    @DisplayName("Fees：ASIN 为空或售价非正应抛 IllegalArgumentException")
    void feesMockValidation() {
        FeesMockClient client = new FeesMockClient();
        assertThrows(IllegalArgumentException.class, () -> client.estimateFbaFees(
                SHOP_ID, MARKETPLACE_ID, "  ", "SKU", new BigDecimal("10.00"), "USD"));
        assertThrows(IllegalArgumentException.class, () -> client.estimateFbaFees(
                SHOP_ID, MARKETPLACE_ID, "B0X", "SKU", BigDecimal.ZERO, "USD"));
        assertThrows(IllegalArgumentException.class, () -> client.estimateFbaFees(
                SHOP_ID, MARKETPLACE_ID, "B0X", "SKU", new BigDecimal("-1.00"), "USD"));
    }

    @Test
    @DisplayName("Fees：按 SKU 估价（结算原表只带 SKU，费用比对走这条）")
    void feesMockBySku() {
        FeesMockClient client = new FeesMockClient();

        FeeEstimate bySku = client.estimateFbaFees(SHOP_ID, MARKETPLACE_ID,
                FeesClient.ID_TYPE_SKU, "SKU-ALPHA", null, new BigDecimal("29.99"), "USD");
        assertNull(bySku.getAsin(), "SKU 估价场景不应伪造 ASIN");
        assertEquals("SKU-ALPHA", bySku.getSku());
        assertEquals(new BigDecimal("10.37"), bySku.getTotalFees());

        // 显式传 sku 时以传入值为准（idValue 可与其他标识混用）
        FeeEstimate explicit = client.estimateFbaFees(SHOP_ID, MARKETPLACE_ID,
                "sku", "SKU-ID", "SKU-REAL", new BigDecimal("8.00"), "USD");
        assertEquals("SKU-REAL", explicit.getSku());

        // ASIN 模式仍按 ASIN 记录
        FeeEstimate byAsin = client.estimateFbaFees(SHOP_ID, MARKETPLACE_ID,
                FeesClient.ID_TYPE_ASIN, "B0ALPHA", "SKU-ALPHA", new BigDecimal("29.99"), "USD");
        assertEquals("B0ALPHA", byAsin.getAsin());
        assertEquals("SKU-ALPHA", byAsin.getSku());
    }

    @Test
    @DisplayName("Fees：不支持的标识类型应拒绝（避免把 SKU 当 ASIN 发出去）")
    void feesMockRejectsUnknownIdType() {
        FeesMockClient client = new FeesMockClient();
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> client.estimateFbaFees(SHOP_ID, MARKETPLACE_ID,
                        "UPC", "012345678901", null, new BigDecimal("10.00"), "USD"));
        assertTrue(ex.getMessage().contains("UPC"), ex.getMessage());
    }
}
