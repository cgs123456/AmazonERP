package com.amz.parse;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 结算原表解析器单元测试。
 * <p>
 * 覆盖：按表头名定位列（列序变动不影响）、行级容错（脏行不中断整批）、
 * 幂等指纹确定性、结构性问题直接抛错。
 */
@DisplayName("SettlementParser 结算原表解析测试")
class SettlementParserTest {

    private static final String HEADER = String.join("\t",
            "settlement-id", "settlement-start-date", "settlement-end-date", "deposit-date",
            "currency", "transaction-type", "order-id", "sku", "amount-type", "amount");

    private static String tsv(String... rows) {
        return HEADER + "\n" + String.join("\n", rows) + "\n";
    }

    private static String row(String settlementId, String currency, String txType, String orderId,
                              String sku, String amountType, String amount) {
        return String.join("\t", settlementId, "2026-09-01T00:00:00Z", "2026-09-07T23:59:59Z",
                "2026-09-08T00:00:00Z", currency, txType, orderId, sku, amountType, amount);
    }

    @Test
    @DisplayName("正常解析：字段映射正确、数据行数准确")
    void parseNormal() {
        String content = tsv(
                row("900001", "USD", "Order", "111-0001", "SKU-ALPHA", "Principal", "29.99"),
                row("900001", "USD", "Order", "111-0001", "SKU-ALPHA", "Commission", "-4.50"),
                row("900001", "USD", "Refund", "111-0002", "SKU-BETA", "Principal", "-49.99"));

        SettlementParser.ParseResult result = SettlementParser.parse(content);
        assertEquals(3, result.getDataLineCount());
        assertEquals(3, result.getRows().size());
        assertTrue(result.getErrors().isEmpty());

        SettlementRow first = result.getRows().get(0);
        assertEquals("900001", first.getSettlementId());
        assertEquals("111-0001", first.getOrderId());
        assertEquals("SKU-ALPHA", first.getSku());
        assertEquals("Order", first.getTransactionType());
        assertEquals("Principal", first.getAmountType());
        assertEquals(new BigDecimal("29.99"), first.getAmount());
        assertEquals("USD", first.getCurrency());
        assertEquals("2026-09-08T00:00:00Z", first.getDepositDate());
        assertTrue(first.getRowKey() != null && first.getRowKey().length() == 32);
    }

    @Test
    @DisplayName("列顺序变化不影响解析（按表头名定位，不按列号）")
    void parseWithReorderedHeader() {
        String reordered = String.join("\t",
                "amount", "currency", "sku", "order-id", "amount-type", "transaction-type", "settlement-id")
                + "\n" + String.join("\t", "29.99", "USD", "SKU-X", "111-9", "Principal", "Order", "900009");

        SettlementParser.ParseResult result = SettlementParser.parse(reordered);
        assertEquals(1, result.getRows().size());
        SettlementRow r = result.getRows().get(0);
        assertEquals(new BigDecimal("29.99"), r.getAmount());
        assertEquals("USD", r.getCurrency());
        assertEquals("SKU-X", r.getSku());
        assertEquals("111-9", r.getOrderId());
        assertEquals("900009", r.getSettlementId());
    }

    @Test
    @DisplayName("调账行订单号为空时正常解析（Adjustment 归属不了订单）")
    void parseAdjustmentWithoutOrder() {
        String content = tsv(
                row("900001", "USD", "Adjustment", "", "SKU-GAMMA", "FBA Inventory Reimbursement", "12.50"));

        SettlementParser.ParseResult result = SettlementParser.parse(content);
        assertEquals(1, result.getRows().size());
        assertNull(result.getRows().get(0).getOrderId());
        assertEquals("Adjustment", result.getRows().get(0).getTransactionType());
    }

    @Test
    @DisplayName("表头缺必需列：抛 IllegalArgumentException 且提示缺失列名")
    void parseMissingRequiredColumn() {
        String bad = "settlement-id\torder-id\tsku\tamount\n900001\t111-1\tSKU\t29.99\n";
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> SettlementParser.parse(bad));
        assertTrue(ex.getMessage().contains("transaction-type"), ex.getMessage());
        assertTrue(ex.getMessage().contains("amount-type"), ex.getMessage());
    }

    @Test
    @DisplayName("行级容错：金额非法/列数不足的行只记账并跳过，其余行照常解析")
    void parseRowLevelTolerance() {
        String content = tsv(
                row("900001", "USD", "Order", "111-1", "SKU-A", "Principal", "29.99"),
                row("900001", "USD", "Order", "111-2", "SKU-B", "Principal", "not-a-number"),
                "900001\tshort\trow",
                row("900001", "USD", "Order", "111-3", "SKU-C", "Principal", "10.00"));

        SettlementParser.ParseResult result = SettlementParser.parse(content);
        assertEquals(2, result.getRows().size(), "两条有效行应被保留");
        assertEquals(2, result.getErrors().size(), "两条脏行应被记录");
        assertEquals(4, result.getDataLineCount(), "数据行数含脏行");
        assertTrue(result.getErrors().stream().anyMatch(e -> e.getReason().contains("amount 非数字")));
        assertTrue(result.getErrors().stream().anyMatch(e -> e.getReason().contains("列数不足")));
        // 行号指向文件真实行（表头为第 1 行）
        assertTrue(result.getErrors().get(0).getLineNo() >= 2);
    }

    @Test
    @DisplayName("空内容 / 仅空白：抛 IllegalArgumentException")
    void parseBlankContent() {
        assertThrows(IllegalArgumentException.class, () -> SettlementParser.parse(null));
        assertThrows(IllegalArgumentException.class, () -> SettlementParser.parse("   \n  \n"));
    }

    @Test
    @DisplayName("幂等指纹：同一行稳定、内容不同则不同（存款日不同也算不同行）")
    void rowKeyDeterminism() {
        SettlementParser.ParseResult first = SettlementParser.parse(tsv(
                row("900001", "USD", "Order", "111-1", "SKU-A", "Principal", "29.99")));
        SettlementParser.ParseResult second = SettlementParser.parse(tsv(
                row("900001", "USD", "Order", "111-1", "SKU-A", "Principal", "29.99")));
        assertEquals(first.getRows().get(0).getRowKey(), second.getRows().get(0).getRowKey(),
                "重复导入同一行必须生成相同指纹");

        SettlementRow a = first.getRows().get(0);
        SettlementRow b = new SettlementRow();
        b.setSettlementId(a.getSettlementId());
        b.setOrderId(a.getOrderId());
        b.setSku(a.getSku());
        b.setAmountType(a.getAmountType());
        b.setAmount(a.getAmount());
        b.setDepositDate("2026-09-15T00:00:00Z");
        assertNotEquals(a.getRowKey(), SettlementParser.rowKey(b), "存款日不同应视为不同行");
    }

    @Test
    @DisplayName("CRLF 换行与空行：正常解析且不计入数据行")
    void parseCrlfAndBlankLines() {
        String content = HEADER + "\r\n"
                + row("900001", "USD", "Order", "111-1", "SKU-A", "Principal", "29.99") + "\r\n"
                + "\r\n";
        SettlementParser.ParseResult result = SettlementParser.parse(content);
        assertEquals(1, result.getDataLineCount());
        assertEquals(1, result.getRows().size());
        assertEquals(new BigDecimal("29.99"), result.getRows().get(0).getAmount());
    }

    @Test
    @DisplayName("金额符号保留：负数不被改写（退款与扣费依赖方向）")
    void parseKeepsSigns() {
        List<SettlementRow> rows = SettlementParser.parse(tsv(
                row("900001", "USD", "Order", "111-1", "SKU-A", "Commission", "-4.50"),
                row("900001", "USD", "Adjustment", "", "SKU-B", "FBA Inventory Reimbursement", "12.50")))
                .getRows();
        assertEquals(new BigDecimal("-4.50"), rows.get(0).getAmount());
        assertEquals(new BigDecimal("12.50"), rows.get(1).getAmount());
        assertEquals(0, new BigDecimal("-4.50").compareTo(rows.get(0).getAmount()));
    }
}
