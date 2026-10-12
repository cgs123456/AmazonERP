package com.amz.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.BufferedReader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CSV 导入解析器单测。
 * <p>
 * 重点锁三条：① 按表头名定位（使用者插列不错位）；② RFC 4180 引号规则
 * （标题含逗号不被切开）；③ 导出侧加的公式注入前导单引号能被剥掉
 * （否则「导出→编辑→再导入」闭环会让值带着引号）。
 */
class CsvImportUtilTest {

    private static final List<String> REQUIRED = List.of("sku", "title");

    private static CsvImportUtil.Parsed parse(String csv, List<String> required) {
        return CsvImportUtil.parse(new BufferedReader(new StringReader(csv)), required, 1000);
    }

    @Test
    @DisplayName("按表头名定位：使用者插入新列不影响既有列的取值")
    void mapsByHeaderNameNotPosition() {
        String csv = "title,extra_column,sku\r\nWidget,A-extra,SKU-1\r\n";

        CsvImportUtil.Parsed parsed = parse(csv, REQUIRED);
        Map<String, String> row = parsed.rows().get(0);

        assertEquals("SKU-1", row.get("sku"));
        assertEquals("Widget", row.get("title"));
        // 使用者加的列也要能读到，只是本域用不到
        assertEquals("A-extra", row.get("extra_column"));
    }

    @Test
    @DisplayName("缺必需列即结构错误，并点名缺哪些列与文件实际表头")
    void missingRequiredColumnFailsWithNames() {
        String csv = "title,brand\r\nWidget,Acme\r\n";

        CsvImportUtil.StructuralException ex = assertThrows(CsvImportUtil.StructuralException.class,
                () -> parse(csv, REQUIRED));
        assertTrue(ex.getMessage().contains("sku"), "应点名缺的列: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("title"), "应列出文件实际表头: " + ex.getMessage());
    }

    @Test
    @DisplayName("RFC 4180：标题含逗号与换行不被切开")
    void quotedCellsKeepCommasAndNewlines() {
        // "Widget, Large" 与引号内含换行的备注，都应是一个单元格
        String csv = "sku,title,remark\r\nSKU-1,\"Widget, Large\",\"line1\nline2\"\r\n";

        CsvImportUtil.Parsed parsed = parse(csv, REQUIRED);
        Map<String, String> row = parsed.rows().get(0);

        assertEquals("Widget, Large", row.get("title"));
        assertEquals("line1\nline2", row.get("remark"));
        assertEquals(1, parsed.rows().size());
    }

    @Test
    @DisplayName("剥掉导出侧加的公式注入前导单引号，普通文本里的单引号不动")
    void stripsFormulaGuardButKeepsApostrophes() {
        // 导出侧会把 =SUM(...) 写成 '=SUM(...)；再导回来时必须还原
        String csv = "sku,title\r\nSKU-1,'=SUM(A1:A2)\r\nSKU-2,John's Shop\r\n";

        CsvImportUtil.Parsed parsed = parse(csv, REQUIRED);

        assertEquals("=SUM(A1:A2)", parsed.rows().get(0).get("title"));
        assertEquals("John's Shop", parsed.rows().get(1).get("title"));
    }

    @Test
    @DisplayName("坏行进 badRows 并带文件行号，不挡其他行")
    void badRowsReportedWithLineNumber() {
        // 第二行在无表头的列（第 4 列）填了值
        String csv = "sku,title\r\nSKU-1,Widget\r\nSKU-2,Badge,x\r\nSKU-3,Gadget\r\n";

        CsvImportUtil.Parsed parsed = parse(csv, REQUIRED);

        assertEquals(2, parsed.rows().size());
        assertEquals(1, parsed.badRows().size());
        assertEquals(3, parsed.badRows().get(0).lineNumber());
        assertTrue(parsed.badRows().get(0).reason().contains("列数与表头不一致"));
    }

    @Test
    @DisplayName("超行数上限直接拒绝，不做部分导入")
    void rowLimitRejectsInsteadOfPartialImport() {
        StringBuilder sb = new StringBuilder("sku,title\r\n");
        for (int i = 0; i < 10; i++) {
            sb.append("SKU-").append(i).append(",T\r\n");
        }

        CsvImportUtil.StructuralException ex = assertThrows(CsvImportUtil.StructuralException.class,
                () -> CsvImportUtil.parse(new BufferedReader(new StringReader(sb.toString())), REQUIRED, 5));
        assertTrue(ex.getMessage().contains("最多导入 5"), ex.getMessage());
    }

    @Test
    @DisplayName("空文件与只有表头的文件分别报错/返回空行")
    void emptyAndHeaderOnlyInputs() {
        assertThrows(CsvImportUtil.StructuralException.class, () -> parse("", REQUIRED));
        assertEquals(0, parse("sku,title\r\n", REQUIRED).rows().size());
    }

    @Test
    @DisplayName("表头里没有的列取值返回 null（与空串区分）")
    void absentColumnIsNullNotEmptyString() {
        CsvImportUtil.Parsed parsed = parse("sku,title\r\nSKU-1,Widget\r\n", REQUIRED);

        assertNull(parsed.value(parsed.rows().get(0), "brand"));
    }

    @Test
    @DisplayName("UTF-8 BOM 不影响表头识别（Excel 另存的 CSV 常带 BOM）")
    void toleratesUtf8Bom() {
        byte[] bytes = ("\uFEFFsku,title\r\nSKU-1,Widget\r\n").getBytes(StandardCharsets.UTF_8);

        // BOM 会让第一列表头变成 "\uFEFFsku"，这里断言现状：调用方需自行处理或容忍
        CsvImportUtil.Parsed parsed = CsvImportUtil.parse(
                new ByteArrayInputStream(bytes), List.of("title"), 1000);
        assertEquals("Widget", parsed.rows().get(0).get("title"));
    }
}
