package com.amz.client.parse;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 销量报表解析器：{@code GET_SALES_AND_TRAFFIC_REPORT} 的 child rows。
 * <p>
 * <b>为什么要单独一个解析器</b>：这张表是 {@code amz_sales_history} 的唯一写入来源，
 * 而 {@code ReplenishmentEngine} 只读它。此前全仓零写入方（V11 迁移注释自己记着
 * 「这张表从未被写入过」），补货建议因此一直基于空历史出数——看起来在跑，实则空转。
 * <p>
 * <b>口径（写进断言，防后人改坏）</b>：
 * <ul>
 *   <li>只取 {@code salesByAsin} / {@code salesBySku} 的 data 段，忽略 header 汇总行
 *       ——汇总行与明细行同表出现，混读会让单日销量翻倍；</li>
 *   <li>同日同 SKU 多行（多站点/多币种拆分）必须合并，不能后者覆盖前者；</li>
 *   <li>销量为 0 的行照样入库——「卖了 0 件」与「这天没数据」对基线计算是两件事，
 *       缺日期按 0 处理是消费侧的口径，写入侧不该替它抹平；</li>
 *   <li>日期非法/销量非数字的行逐条报错并跳过，不整批失败。</li>
 * </ul>
 */
@DisplayName("销量报表解析器：只信明细行，同日同 SKU 合并")
class SalesTrafficParserTest {

    private static final String HEADER =
            "startDate\tendDate\tasin\tparentAsin\tsku\tunitsOrdered\torderedProductSales\t"
                    + "totalOrderItems\tcurrency\tmarketplaceId";

    @Test
    @DisplayName("标准明细行解析出日期/SKU/销量/金额")
    void parsesDetailRows() {
        String content = HEADER + "\n"
                + "2026-09-01\t2026-09-01\tB001\tB001\tSKU-1\t12\t599.88\t12\tUSD\tATVPDKIKX0DER";

        SalesTrafficParser.ParseResult result = SalesTrafficParser.parse(content);

        assertEquals(1, result.getRows().size());
        SalesTrafficParser.SalesRow row = result.getRows().get(0);
        assertEquals("2026-09-01", row.saleDate().toString());
        assertEquals("SKU-1", row.sku());
        assertEquals(12, row.units());
        assertEquals(0, row.revenue().compareTo(new java.math.BigDecimal("599.88")));
        assertTrue(result.getErrors().isEmpty(), "合法行不应报错：" + result.getErrors());
    }

    @Test
    @DisplayName("汇总行不得混入明细（同表出现，混读会让单日销量翻倍）")
    void summaryRowsAreNotMixedIntoDetails() {
        // Amazon 的 report 常在同一文件里带汇总段；parentAsin 为空是其特征之一，
        // 但更可靠的判据是这段数据没有 sku 明细语义——本解析器只认 data 段。
        String content = HEADER + "\n"
                + "2026-09-01\t2026-09-30\t\t\t\tTOTAL\t9999.99\t\tUSD\tATVPDKIKX0DER\n"
                + "2026-09-01\t2026-09-01\tB001\tB001\tSKU-1\t12\t599.88\t12\tUSD\tATVPDKIKX0DER";

        SalesTrafficParser.ParseResult result = SalesTrafficParser.parse(content);

        assertEquals(1, result.getRows().size(), "汇总行不得计入明细");
        assertEquals("SKU-1", result.getRows().get(0).sku());
    }

    @Test
    @DisplayName("同日同 SKU 多行合并销量与金额，不后者覆盖前者")
    void sameDaySameSkuRowsAreMerged() {
        String content = HEADER + "\n"
                + "2026-09-01\t2026-09-01\tB001\tB001\tSKU-1\t5\t100.00\t5\tUSD\tATVPDKIKX0DER\n"
                + "2026-09-01\t2026-09-01\tB001\tB001\tSKU-1\t7\t140.00\t7\tUSD\tA1F83G8C2ARO7P";

        SalesTrafficParser.ParseResult result = SalesTrafficParser.parse(content);

        assertEquals(1, result.getRows().size(), "同日同 SKU 必须合并为一行");
        assertEquals(12, result.getRows().get(0).units());
        assertEquals(0, result.getRows().get(0).revenue()
                .compareTo(new java.math.BigDecimal("240.00")));
    }

    @Test
    @DisplayName("销量 0 的行照样入库（「卖了 0 件」≠「这天没数据」）")
    void zeroUnitsRowIsKept() {
        String content = HEADER + "\n"
                + "2026-09-01\t2026-09-01\tB001\tB001\tSKU-1\t0\t0.00\t0\tUSD\tATVPDKIKX0DER";

        SalesTrafficParser.ParseResult result = SalesTrafficParser.parse(content);

        assertEquals(1, result.getRows().size(), "0 销量行必须保留");
        assertEquals(0, result.getRows().get(0).units());
    }

    @Test
    @DisplayName("日期非法的行逐条报错并跳过，不整批失败")
    void invalidDateIsReportedPerRow() {
        String content = HEADER + "\n"
                + "not-a-date\t2026-09-01\tB001\tB001\tSKU-1\t3\t30.00\t3\tUSD\tATVPDKIKX0DER\n"
                + "2026-09-01\t2026-09-01\tB001\tB001\tSKU-2\t4\t40.00\t4\tUSD\tATVPDKIKX0DER";

        SalesTrafficParser.ParseResult result = SalesTrafficParser.parse(content);

        assertEquals(1, result.getRows().size(), "坏行跳过，好行仍要入库");
        assertEquals("SKU-2", result.getRows().get(0).sku());
        assertEquals(1, result.getErrors().size(), "坏行必须报错：" + result.getErrors());
        assertTrue(result.getErrors().get(0).reason().contains("日期"),
                "错误要点名是日期问题，便于定位：" + result.getErrors().get(0).reason());
    }

    @Test
    @DisplayName("销量非数字：按 0 处理并报错（不猜、不静默吞）")
    void nonNumericUnitsIsReported() {
        String content = HEADER + "\n"
                + "2026-09-01\t2026-09-01\tB001\tB001\tSKU-1\tabc\t599.88\t12\tUSD\tATVPDKIKX0DER";

        SalesTrafficParser.ParseResult result = SalesTrafficParser.parse(content);

        assertEquals(1, result.getRows().size(), "销量坏但日期 SKU 可信，仍入库");
        assertEquals(0, result.getRows().get(0).units());
        assertEquals(1, result.getErrors().size());
    }

    @Test
    @DisplayName("缺 SKU 的行无法归属，跳过并报错")
    void rowWithoutSkuIsSkipped() {
        String content = HEADER + "\n"
                + "2026-09-01\t2026-09-01\tB001\tB001\t\t12\t599.88\t12\tUSD\tATVPDKIKX0DER";

        SalesTrafficParser.ParseResult result = SalesTrafficParser.parse(content);

        assertTrue(result.getRows().isEmpty(), "无 SKU 的行无法写入 amz_sales_history");
        assertEquals(1, result.getErrors().size());
    }

    @Test
    @DisplayName("空文件/只有表头：返回空结果而非异常")
    void emptyContentIsNotAnError() {
        assertTrue(SalesTrafficParser.parse("").getRows().isEmpty());
        assertTrue(SalesTrafficParser.parse(HEADER).getRows().isEmpty());
        assertTrue(SalesTrafficParser.parse(HEADER + "\n").getErrors().isEmpty());
    }

    @Test
    @DisplayName("表头缺失必需的日期列：整批拒绝，不猜列位置")
    void missingRequiredColumnFailsFast() {
        // 没有 startDate 列时，任何「按位置取第一列」的做法都会把 asin 当日期写进去。
        String content = "asin\tsku\tunitsOrdered\nB001\tSKU-1\t12";

        assertThrows(IllegalArgumentException.class, () -> SalesTrafficParser.parse(content));
    }

    @Test
    @DisplayName("CRLF 与列数不足的行都被容忍，不抛未预期异常")
    void crlfAndShortRowsAreTolerated() {
        String content = HEADER + "\r\n"
                + "2026-09-01\t2026-09-01\tB001\tB001\tSKU-1\t12\t599.88\t12\tUSD\tATVPDKIKX0DER\r\n"
                + "2026-09-02\t2026-09-02\tB002\tB002\tSKU-2\t3\r\n";

        SalesTrafficParser.ParseResult result = SalesTrafficParser.parse(content);

        assertEquals(1, result.getRows().size(),
                "列数不足的行必须整行跳过——缺的正是尾列（金额/站点），静默收下会产出半条数据");
        assertEquals("SKU-1", result.getRows().get(0).sku());
        assertEquals(1, result.getErrors().size(), "列数不足要报错：" + result.getErrors());
        assertTrue(result.getErrors().get(0).reason().contains("列数"),
                "错误要点名是列数问题：" + result.getErrors().get(0).reason());
    }

    @Test
    @DisplayName("金额列缺失时按 0 计，不影响销量入库")
    void missingRevenueDefaultsToZero() {
        String content = HEADER + "\n"
                + "2026-09-01\t2026-09-01\tB001\tB001\tSKU-1\t12\t\t12\tUSD\tATVPDKIKX0DER";

        SalesTrafficParser.ParseResult result = SalesTrafficParser.parse(content);

        assertEquals(1, result.getRows().size());
        assertEquals(12, result.getRows().get(0).units());
        assertEquals(0, result.getRows().get(0).revenue()
                .compareTo(java.math.BigDecimal.ZERO));
    }

    @Test
    @DisplayName("大小写/顺序无关：按表头名取列，不按位置")
    void columnsAreLocatedByNameNotPosition() {
        String reordered = "sku\tstartDate\tendDate\tunitsOrdered\torderedProductSales\t"
                + "asin\tparentAsin\ttotalOrderItems\tcurrency\tmarketplaceId\n"
                + "SKU-9\t2026-09-05\t2026-09-05\t8\t88.00\tB009\tB009\t8\tUSD\tATVPDKIKX0DER";

        SalesTrafficParser.ParseResult result = SalesTrafficParser.parse(reordered);

        assertEquals(1, result.getRows().size());
        assertEquals("SKU-9", result.getRows().get(0).sku());
        assertEquals("2026-09-05", result.getRows().get(0).saleDate().toString());
        assertEquals(8, result.getRows().get(0).units());
    }

    @Test
    @DisplayName("汇总行识别：parentAsin/sku 全空且 units 非数字时不得入库")
    void totalRowWithoutSkuIsRejected() {
        String content = HEADER + "\n"
                + "2026-09-01\t2026-09-30\t\t\t\tTOTAL\t9999.99\t\tUSD\tATVPDKIKX0DER";

        SalesTrafficParser.ParseResult result = SalesTrafficParser.parse(content);

        assertTrue(result.getRows().isEmpty(), "汇总行没有 SKU 语义，不得写入销量历史");
        assertFalse(result.getErrors().isEmpty(), "汇总行应被报出，便于发现报表形态变化");
    }

    @Test
    @DisplayName("List 返回可安全遍历（不为 null）")
    void neverReturnsNullCollections() {
        SalesTrafficParser.ParseResult result = SalesTrafficParser.parse(HEADER);
        List<SalesTrafficParser.SalesRow> rows = result.getRows();
        assertEquals(0, rows.size());
        assertEquals(0, result.getErrors().size());
    }
}
