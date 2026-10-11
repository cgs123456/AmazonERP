package com.amz.parse;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * FBA 客户退货报表解析契约。
 * <p>
 * <b>列依据</b>：官方 Report Type Values 文档给出的 13 列
 * （return-date / order-id / sku / asin / fnsku / product-name / quantity /
 * fulfillment-center-id / detailed-disposition / reason / status /
 * license-plate-number / customer-comments）。
 * <p>
 * 本测试钉住三件「错了会静默做坏事」的事：
 * <ol>
 *   <li><b>按表头名定位列</b>——列序变了也不能读错（按列号取值会静默错位）；</li>
 *   <li><b>处置只认 SELLABLE 为良品</b>，其余一律 DEFECTIVE（不虚增可售库存）；</li>
 *   <li><b>幂等键稳定且不含易变字段</b>——同一行重复解析得到同一键；
 *       disposition/status 变化不改变键（否则同一笔退货的更新会被当成新行）。</li>
 * </ol>
 * 变异验证（写本测试时实际注入确认会红）：把处置映射改成「非空即可售」→ 第二例红；
 * 把 externalKey 里加入 disposition → 第三例红。
 */
@DisplayName("FBA 客户退货报表：按表头定位列 + 保守处置映射 + 稳定幂等键")
class CustomerReturnsParserTest {

    private static final String HEADER = String.join("\t",
            "return-date", "order-id", "sku", "asin", "fnsku", "product-name", "quantity",
            "fulfillment-center-id", "detailed-disposition", "reason", "status",
            "license-plate-number", "customer-comments");

    private String row(String returnDate, String orderId, String sku, String qty,
                       String disposition, String reason, String status) {
        return String.join("\t", returnDate, orderId, sku, "B0KMDRQTX7", "X00FN", "无线耳机", qty,
                "FBA1", disposition, reason, status, "LPN1", "customer said broken");
    }

    @Test
    @DisplayName("解析官方 13 列：字段正确落位")
    void parsesOfficialColumns() {
        String tsv = HEADER + "\n"
                + row("2026-10-01", "114-0000000-7599439", "SKU-1", "2", "SELLABLE", "No longer needed", "Unit returned to inventory");

        CustomerReturnsParser.ParseResult result = CustomerReturnsParser.parse(tsv);

        assertEquals(1, result.rows().size());
        assertEquals(0, result.errors().size());
        CustomerReturnsParser.Row r = result.rows().get(0);
        assertEquals("2026-10-01", r.getReturnDate());
        assertEquals("114-0000000-7599439", r.getOrderId());
        assertEquals("SKU-1", r.getSku());
        assertEquals("B0KMDRQTX7", r.getAsin());
        assertEquals(2, r.getQuantity());
        assertEquals("No longer needed", r.getReason());
        assertEquals("Unit returned to inventory", r.getStatus());
    }

    @Test
    @DisplayName("列序打乱仍按表头名解析（不按列号）")
    void resolvesColumnsByNameNotPosition() {
        // 把 quantity 与 sku 位置对调，表头同步对调
        String header = String.join("\t",
                "return-date", "order-id", "quantity", "sku", "asin", "fnsku", "product-name",
                "fulfillment-center-id", "detailed-disposition", "reason", "status",
                "license-plate-number", "customer-comments");
        String data = String.join("\t", "2026-10-01", "114-1", "3", "SKU-9", "B0X", "X1", "商品",
                "FBA1", "SELLABLE", "r", "s", "LPN", "c");

        CustomerReturnsParser.ParseResult result = CustomerReturnsParser.parse(header + "\n" + data);

        assertEquals("SKU-9", result.rows().get(0).getSku());
        assertEquals(3, result.rows().get(0).getQuantity());
    }

    @Test
    @DisplayName("处置映射：只有 SELLABLE 判良品，其余（含未知）一律 DEFECTIVE")
    void dispositionIsConservative() {
        assertEquals("GOOD", CustomerReturnsParser.mapDisposition("SELLABLE"));
        assertEquals("GOOD", CustomerReturnsParser.mapDisposition(" sellable "));

        // 官方可能出现的其它处置（残次/损坏/过期）与任何未知值都必须落到 DEFECTIVE
        for (String other : List.of("DEFECTIVE", "CUSTOMER_DAMAGED", "CARRIER_DAMAGED",
                "EXPIRED", "SOMETHING_NEW", "", "  ")) {
            assertEquals("DEFECTIVE", CustomerReturnsParser.mapDisposition(other),
                    "非 SELLABLE 的处置必须判为不可售（不虚增可售库存），实际输入：" + other);
        }
    }

    @Test
    @DisplayName("幂等键稳定：同一行业务字段相同即同键，且不含 disposition/status")
    void externalKeyIsStableAndExcludesVolatileFields() {
        String tsv1 = HEADER + "\n"
                + row("2026-10-01", "114-1", "SKU-1", "2", "SELLABLE", "r", "Unit returned to inventory");
        // 同一笔退货：disposition 与 status 后来变了（Amazon 处理进展）
        String tsv2 = HEADER + "\n"
                + row("2026-10-01", "114-1", "SKU-1", "2", "DEFECTIVE", "r", "Reimbursed");

        String key1 = CustomerReturnsParser.parse(tsv1).rows().get(0).getExternalKey();
        String key2 = CustomerReturnsParser.parse(tsv2).rows().get(0).getExternalKey();

        assertEquals(key1, key2,
                "disposition/status 变化不得改变幂等键——否则同一笔退货的更新会被当成新行、重复回补库存");
        assertTrue(key1.matches("[0-9a-f]{32}"), "幂等键应为 MD5 hex");
    }

    @Test
    @DisplayName("幂等键区分不同退货（日期/订单/SKU 任一不同即不同键）")
    void externalKeyDistinguishesDifferentReturns() {
        String base = HEADER + "\n" + row("2026-10-01", "114-1", "SKU-1", "1", "SELLABLE", "r", "s");
        String otherDate = HEADER + "\n" + row("2026-10-02", "114-1", "SKU-1", "1", "SELLABLE", "r", "s");
        String otherOrder = HEADER + "\n" + row("2026-10-01", "114-2", "SKU-1", "1", "SELLABLE", "r", "s");
        String otherSku = HEADER + "\n" + row("2026-10-01", "114-1", "SKU-2", "1", "SELLABLE", "r", "s");

        String k = key(base);
        assertNotEquals(k, key(otherDate));
        assertNotEquals(k, key(otherOrder));
        assertNotEquals(k, key(otherSku));
    }

    @Test
    @DisplayName("必需列缺失：抛结构性错误（不静默返回空）")
    void rejectsMissingRequiredColumns() {
        String header = String.join("\t", "return-date", "asin", "quantity"); // 缺 order-id/sku
        String tsv = header + "\n" + String.join("\t", "2026-10-01", "B0X", "1");

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> CustomerReturnsParser.parse(tsv));
        assertTrue(e.getMessage().contains("order-id") || e.getMessage().contains("sku"),
                "错误必须点名缺失的列，实际：" + e.getMessage());
    }

    @Test
    @DisplayName("行级容错：脏行只记账不中断整批")
    void toleratesDirtyRows() {
        String tsv = HEADER + "\n"
                + row("2026-10-01", "114-1", "SKU-1", "1", "SELLABLE", "r", "s") + "\n"
                + row("2026-10-01", "114-2", "SKU-2", "abc", "SELLABLE", "r", "s") + "\n"
                + row("2026-10-01", "114-3", "SKU-3", "1", "SELLABLE", "r", "s");

        CustomerReturnsParser.ParseResult result = CustomerReturnsParser.parse(tsv);

        assertEquals(2, result.rows().size(), "好行必须保留");
        assertEquals(1, result.errors().size(), "脏行记账");
        assertTrue(result.errors().get(0).message().contains("quantity"));
    }

    @Test
    @DisplayName("quantity 非正或为空：拒绝该行（不建 0 件退货单）")
    void rejectsNonPositiveQuantity() {
        String tsv = HEADER + "\n"
                + row("2026-10-01", "114-1", "SKU-1", "0", "SELLABLE", "r", "s") + "\n"
                + row("2026-10-01", "114-2", "SKU-2", "", "SELLABLE", "r", "s");

        CustomerReturnsParser.ParseResult result = CustomerReturnsParser.parse(tsv);

        assertEquals(0, result.rows().size());
        assertEquals(2, result.errors().size());
    }

    @Test
    @DisplayName("空内容/无表头：抛错")
    void rejectsEmpty() {
        assertThrows(IllegalArgumentException.class, () -> CustomerReturnsParser.parse(""));
        assertThrows(IllegalArgumentException.class, () -> CustomerReturnsParser.parse("   "));
        assertThrows(IllegalArgumentException.class, () -> CustomerReturnsParser.parse(null));
    }

    private String key(String tsv) {
        return CustomerReturnsParser.parse(tsv).rows().get(0).getExternalKey();
    }
}
