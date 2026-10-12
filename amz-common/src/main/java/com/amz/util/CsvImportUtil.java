package com.amz.util;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * CSV 导入解析器（按表头名定位列）。
 *
 * <p>与 {@link CsvExportUtil} 的导出侧对称：导出加前导单引号防公式注入，
 * 导入侧要能把前导单引号去掉，否则导出的文件再导回来时值会带着引号。
 *
 * <p><b>为什么按表头名定位而不是列序号</b>：使用者在 Excel 里插一列是常规操作，
 * 按序号定位会让「SKU 列」读到「品牌列」的值，而且是静默错位——比报错危险得多。
 *
 * <p>不校验业务规则（必填/格式/取值），那些由调用方按域处理；这里只保证
 * 「结构对不对、每行有哪些字段、坏行是哪一行」。
 */
public final class CsvImportUtil {

    /** 单文件行数硬上限：超限直接拒绝，不做部分导入（部分导入会让调用方以为导全了）。 */
    public static final int DEFAULT_MAX_ROWS = 10_000;

    /** 文件大小硬上限（字节）：防内存被打爆。 */
    public static final long DEFAULT_MAX_BYTES = 5L * 1024 * 1024;

    private CsvImportUtil() {
    }

    /**
     * 解析结果。三组信息与 {@code ImportReport} 同构：结构错误、行数据、坏行清单。
     *
     * @param headers   表头（原顺序，含空表头时的占位名）
     * @param rows      数据行（下标 0 对应文件里的第 2 行，即首个数据行）
     * @param badRows   无法解析的行：下标 → 原因（含文件行号，便于使用者定位）
     */
    public record Parsed(List<String> headers, List<Map<String, String>> rows,
                         List<BadRow> badRows) {

        /** 按表头名取值；表头里没有这一列时返回 null（而不是空串——两者语义不同）。 */
        public String value(Map<String, String> row, String column) {
            return row.get(column);
        }

        public int totalRows() {
            return rows.size() + badRows.size();
        }
    }

    /** 坏行：为什么坏 + 在文件里的第几行（1 基，含表头行）。 */
    public record BadRow(int lineNumber, String reason) {
    }

    /** 结构错误：缺列/空表头等，整批不可用的情况。 */
    public static class StructuralException extends RuntimeException {
        public StructuralException(String message) {
            super(message);
        }
    }

    /**
     * 解析 CSV 输入流。
     *
     * @param requiredColumns 必须存在的表头；缺任一即抛 {@link StructuralException} 并点名缺哪些
     * @param maxRows         行数上限
     */
    public static Parsed parse(InputStream in, List<String> requiredColumns, int maxRows) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(in, StandardCharsets.UTF_8))) {
            return parse(reader, requiredColumns, maxRows);
        } catch (IOException e) {
            throw new StructuralException("读取上传文件失败：" + e.getMessage());
        }
    }

    /**
     * 按 RFC 4180 解析（支持引号内含逗号/换行/双写引号转义）。
     * <p>
     * 不用 split(",")：使用者从 Excel 另存的 CSV 里，标题含逗号极其常见
     * （"Widget, Large"），按逗号切会把一行切成两列且不报错。
     */
    public static Parsed parse(BufferedReader reader, List<String> requiredColumns, int maxRows) {
        List<String> headers = new ArrayList<>();
        List<Map<String, String>> rows = new ArrayList<>();
        List<BadRow> badRows = new ArrayList<>();

        String line;
        int lineNumber = 0;
        boolean headerRead = false;
        // RFC 4180 允许引号内含换行，所以「一行」不等于「一条记录」；
        // 这里按记录累积，行号取记录起始行
        while (true) {
            String record;
            try {
                record = readRecord(reader);
            } catch (IOException e) {
                throw new StructuralException("读取上传文件失败：" + e.getMessage());
            }
            if (record == null) {
                break;
            }
            lineNumber++;
            if (record.isEmpty()) {
                continue;
            }
            if (!headerRead) {
                headers.addAll(splitCells(record));
                headerRead = true;
                continue;
            }
            if (rows.size() + badRows.size() >= maxRows) {
                throw new StructuralException("单次最多导入 " + maxRows + " 行数据，当前文件超出上限，请分批提交");
            }
            List<String> cells = splitCells(record);
            Map<String, String> row = new LinkedHashMap<>();
            boolean malformed = false;
            // 列数多于表头：多出来的列无列名可定位，若其中有值就是文件与模板不符，
            // 判坏行而不是静默丢弃——静默丢弃会让使用者以为那列导进去了
            if (cells.size() > headers.size()) {
                for (int i = headers.size(); i < cells.size(); i++) {
                    String extra = cells.get(i);
                    if (extra != null && !extra.isBlank()) {
                        malformed = true;
                        break;
                    }
                }
            }
            for (int i = 0; i < headers.size(); i++) {
                String key = headers.get(i);
                String value = i < cells.size() ? cells.get(i) : null;
                if (value != null) {
                    value = stripFormulaGuard(value);
                }
                if (key == null || key.isBlank()) {
                    // 空表头列：有值但无列名，无法按名定位，同样判坏行
                    if (value != null && !value.isBlank()) {
                        malformed = true;
                    }
                    continue;
                }
                row.put(key, value);
            }
            if (malformed) {
                badRows.add(new BadRow(lineNumber,
                        "列数与表头不一致（存在无表头列的值）"));
            } else {
                rows.add(row);
            }
        }

        if (!headerRead) {
            throw new StructuralException("文件为空：没有表头行");
        }
        List<String> missing = new ArrayList<>();
        for (String required : requiredColumns) {
            if (!headers.contains(required)) {
                missing.add(required);
            }
        }
        if (!missing.isEmpty()) {
            throw new StructuralException("缺少必需列：" + String.join("、", missing)
                    + "。文件表头为：" + String.join("、", headers));
        }
        return new Parsed(headers, rows, badRows);
    }

    /**
     * 读一条完整记录（引号内的换行算同一条）。
     *
     * @return null 表示流结束
     */
    private static String readRecord(BufferedReader reader) throws IOException {
        StringBuilder sb = new StringBuilder();
        String line = reader.readLine();
        if (line == null) {
            return null;
        }
        sb.append(line);
        // 引号数为奇数说明引号没闭合，下一行仍是这条记录的内容
        while (countQuotes(sb.toString()) % 2 == 1) {
            String next = reader.readLine();
            if (next == null) {
                break;
            }
            sb.append('\n').append(next);
        }
        return sb.toString();
    }

    private static int countQuotes(String s) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == '"') {
                n++;
            }
        }
        return n;
    }

    /** 按 RFC 4180 切分一条记录为单元格。 */
    static List<String> splitCells(String record) {
        List<String> cells = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < record.length(); i++) {
            char c = record.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < record.length() && record.charAt(i + 1) == '"') {
                        cur.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    cur.append(c);
                }
            } else if (c == '"') {
                inQuotes = true;
            } else if (c == ',') {
                cells.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        cells.add(cur.toString());
        return cells;
    }

    /**
     * 去掉导出侧加的公式注入前导单引号，使「导出 → 编辑 → 再导入」闭环不残留引号。
     * <p>
     * 只在前导字符确实是 {@code = + - @ \t \r} 之一时才剥——普通文本里的单引号
     * （如 "John's Shop"）不能动。
     */
    static String stripFormulaGuard(String value) {
        if (value.length() < 2 || value.charAt(0) != '\'') {
            return value;
        }
        char second = value.charAt(1);
        if (second == '=' || second == '+' || second == '-' || second == '@'
                || second == '\t' || second == '\r') {
            return value.substring(1);
        }
        return value;
    }
}
