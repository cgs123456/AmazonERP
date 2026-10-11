package com.amz.util;

import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * CSV 导出工具（报表导出共用）。
 * <p>
 * <b>三个安全点</b>：
 * <ol>
 *   <li><b>UTF-8 BOM</b>：Excel 打开无 BOM 的 UTF-8 中文必乱码，用户会以为导出坏了；</li>
 *   <li><b>公式注入防护</b>：以 {@code = + - @ \t \r} 开头的单元格加前导单引号——
 *       平台数据（商品标题/买家名/备注）进 Excel 后会被当公式执行（CSV Injection，
 *       OWASP 长期条目），不能因为「数据是自己库里的」就跳过；</li>
 *   <li><b>字段全量引号</b>：值内含逗号/引号/换行时按 RFC 4180 转义，统一加引号最省心。</li>
 * </ol>
 * <b>有界导出</b>：调用方必须带 {@code maxRows} 硬上限；触顶时写一行显式截断说明——
 * 静默截断会让「导出了 10 万行」被当成「全部数据」，那是在撒谎。
 */
public final class CsvExportUtil {

    /** 默认行数硬上限。 */
    public static final int DEFAULT_MAX_ROWS = 100_000;

    private static final String BOM = "\uFEFF";

    private CsvExportUtil() {
    }

    /**
     * 开始一次导出：设置响应头并写 UTF-8 BOM。
     *
     * @param response HTTP 响应
     * @param filename 下载文件名（不含路径；调用方保证无引号等特殊字符）
     * @return 供逐行写入的 PrintWriter（调用方负责 close 或由容器收尾）
     */
    public static PrintWriter begin(HttpServletResponse response, String filename) throws IOException {
        response.setContentType("text/csv; charset=UTF-8");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setHeader("Content-Disposition",
                "attachment; filename=\"" + filename.replace("\"", "") + "\"");
        PrintWriter writer = response.getWriter();
        writer.write(BOM);
        return writer;
    }

    /** 写一行（自动 RFC 4180 转义 + 公式注入防护）。 */
    public static void row(PrintWriter writer, List<?> cells) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cells.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(quote(escapeFormula(cells.get(i))));
        }
        writer.write(sb.toString());
        writer.write("\r\n");
    }

    /** 写显式截断说明行（触顶时调用，绝不静默截断）。 */
    public static void truncatedRow(PrintWriter writer, int maxRows) {
        row(writer, List.of("# 已达导出上限 " + maxRows + " 行，剩余数据未包含；请缩小时间范围或分批导出"));
    }

    /**
     * RFC 4180：含逗号/引号/换行时整体加引号，内部引号翻倍。
     */
    static String quote(String value) {
        String v = value == null ? "" : value;
        if (v.contains(",") || v.contains("\"") || v.contains("\n") || v.contains("\r")) {
            return '"' + v.replace("\"", "\"\"") + '"';
        }
        return v;
    }

    /**
     * 公式注入防护：以公式起始字符开头的单元格加前导单引号。
     * <p>
     * Excel/LibreOffice 会把 {@code =cmd|' /C calc'!A0} 这类单元格当公式执行；
     * 前导单引号让它们保持为文本（Excel 自身导出敏感值也是这个做法）。
     */
    static String escapeFormula(Object cell) {
        String v = cell == null ? "" : String.valueOf(cell);
        if (v.isEmpty()) {
            return v;
        }
        char first = v.charAt(0);
        if (first == '=' || first == '+' || first == '-' || first == '@'
                || first == '\t' || first == '\r') {
            return "'" + v;
        }
        return v;
    }
}
