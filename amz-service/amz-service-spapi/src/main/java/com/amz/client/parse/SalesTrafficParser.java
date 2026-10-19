package com.amz.client.parse;

import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 销量报表解析器：Amazon {@code GET_SALES_AND_TRAFFIC_REPORT} 的 TSV。
 * <p>
 * <b>为什么需要它</b>：{@code amz_sales_history} 此前全仓零写入方，而
 * {@code ReplenishmentEngine} 只读它算基线日均需求。缺这一步，补货建议
 * 一直基于空历史出数——页面看起来在跑，实则空转。
 * <p>
 * <b>三条口径（都有测试钉住）</b>：
 * <ol>
 *   <li><b>按表头名取列，不按位置</b>。Amazon 各报表的列序不稳定，按位置取会把
 *       asin 当日期写进库里。表头缺 {@code startDate} 直接整批拒绝。</li>
 *   <li><b>同日同 SKU 合并</b>。报表按 (date × asin × marketplace) 拆行，
 *       同一 SKU 同一天可能出现多行；后者覆盖前者会让单日销量凭空少一半。</li>
 *   <li><b>0 销量行照样保留</b>。「卖了 0 件」与「这天没数据」对基线计算是两件事，
 *       消费侧（{@code ReplenishmentEngine}）的口径是缺日期按 0 处理，
 *       写入侧不该替它抹平——否则「有数据源的 0」与「无数据」在库里同形。</li>
 * </ol>
 * 汇总行（TOTAL 段）天然没有 SKU，会被 {@link #rowWithoutSku} 规则挡掉，
 * 因此不会把汇总值混进明细。
 */
@Slf4j
public final class SalesTrafficParser {

    /** 报表必需列：缺任一列则整批拒绝，不猜列位置。均为小写（与 {@link #locateColumns} 的 key 一致）。 */
    private static final List<String> REQUIRED_COLUMNS = List.of("startdate", "sku");

    private static final String COL_START_DATE = "startdate";
    private static final String COL_SKU = "sku";
    private static final String COL_UNITS = "unitsordered";
    private static final String COL_REVENUE = "orderedproductsales";

    private SalesTrafficParser() {
    }

    /** 单行销量。{@code saleDate} 已规范为报表的 startDate（当日粒度）。 */
    public record SalesRow(LocalDate saleDate, String sku, int units, BigDecimal revenue) {
    }

    /** 行级错误：哪一行、为什么。保留原始行便于回查报表。 */
    public record RowError(int lineNo, String reason, String rawLine) {
    }

    public static final class ParseResult {
        private final List<SalesRow> rows = new ArrayList<>();
        private final List<RowError> errors = new ArrayList<>();

        public List<SalesRow> getRows() {
            return rows;
        }

        public List<RowError> getErrors() {
            return errors;
        }

        /** 汇总行/被跳过行的计数（供调用方判断报表形态是否变化）。 */
        private int skippedLines = 0;

        public int getSkippedLines() {
            return skippedLines;
        }
    }

    /**
     * 解析报表内容。
     *
     * @param content 已解码的 TSV 文本（{@code ReportDocumentDecoder} 的产物）
     * @return 明细行 + 行级错误；永不返回 null
     * @throws IllegalArgumentException 表头缺必需列——此时按位置取列必然写错数据
     */
    public static ParseResult parse(String content) {
        ParseResult result = new ParseResult();
        if (content == null || content.isBlank()) {
            return result;
        }
        // 按 \n 拆，再各自去掉 \r：报表是 CRLF 时不剥掉会让最后一列带 \r
        String[] lines = content.split("\n");
        if (lines.length == 0) {
            return result;
        }

        Map<String, Integer> columns = locateColumns(lines[0]);
        for (String required : REQUIRED_COLUMNS) {
            if (!columns.containsKey(required)) {
                throw new IllegalArgumentException(
                        "销量报表缺少必需列 " + required + "，实际表头：" + lines[0]
                                + "（按位置取列会把 asin 当日期写进库，故整批拒绝）");
            }
        }

        // 同日同 SKU 累加：key 用「日期|SKU」，保留插入序便于日志回查
        Map<String, SalesRow> merged = new LinkedHashMap<>();
        for (int i = 1; i < lines.length; i++) {
            String raw = stripCr(lines[i]);
            if (raw.isBlank()) {
                continue;
            }
            String[] cells = raw.split("\t", -1);
            // 列数少于表头视为损坏行：少了尾列（金额/站点）时 cell() 会返回 null，
            // 解析器会把它当「缺省」收下——于是一个只有 6 列的行也能入库，
            // 而缺的正是金额。报表形态变化时这会静默产出半条数据。
            if (cells.length < columns.size()) {
                result.errors.add(new RowError(i + 1,
                        "列数 " + cells.length + " 少于表头 " + columns.size() + "，整行跳过", raw));
                continue;
            }
            String dateCell = cell(cells, columns, COL_START_DATE);
            String skuCell = cell(cells, columns, COL_SKU);

            if (skuCell == null || skuCell.isBlank()) {
                // 汇总行（TOTAL 段）没有 SKU 语义；也可能是报表形态变化，两种情况都要看见
                result.skippedLines++;
                result.errors.add(new RowError(i + 1, "缺少 SKU，无法归属到 amz_sales_history", raw));
                continue;
            }
            LocalDate saleDate;
            try {
                saleDate = LocalDate.parse(dateCell);
            } catch (DateTimeParseException | NullPointerException e) {
                result.errors.add(new RowError(i + 1, "日期无法解析：" + dateCell, raw));
                continue;
            }

            String unitsCell = cell(cells, columns, COL_UNITS);
            int units = 0;
            boolean unitsBad = false;
            if (unitsCell != null && !unitsCell.isBlank()) {
                try {
                    units = Integer.parseInt(unitsCell.trim());
                } catch (NumberFormatException e) {
                    unitsBad = true;
                }
            }

            BigDecimal revenue = parseRevenue(cell(cells, columns, COL_REVENUE));

            String key = saleDate + "|" + skuCell.trim();
            SalesRow existing = merged.get(key);
            if (existing == null) {
                merged.put(key, new SalesRow(saleDate, skuCell.trim(), units, revenue));
            } else {
                // 同日同 SKU 累加，不覆盖
                merged.put(key, new SalesRow(saleDate, skuCell.trim(),
                        existing.units() + units, existing.revenue().add(revenue)));
            }
            if (unitsBad) {
                result.errors.add(new RowError(i + 1,
                        "销量非数字，按 0 计入（原值 " + unitsCell + "）", raw));
            }
        }

        result.rows.addAll(merged.values());
        if (!result.errors.isEmpty()) {
            log.warn("销量报表有 {} 行未按预期解析（明细 {} 行仍入库）",
                    result.errors.size(), result.rows.size());
        }
        return result;
    }

    /** 表头名统一小写后建索引；同名列取第一个（报表不应重复）。 */
    private static Map<String, Integer> locateColumns(String headerLine) {
        Map<String, Integer> map = new LinkedHashMap<>();
        String[] headers = stripCr(headerLine).split("\t", -1);
        for (int i = 0; i < headers.length; i++) {
            String name = headers[i].trim().toLowerCase(Locale.ROOT);
            if (!name.isEmpty()) {
                map.putIfAbsent(name, i);
            }
        }
        return map;
    }

    private static String cell(String[] cells, Map<String, Integer> columns, String name) {
        Integer idx = columns.get(name);
        if (idx == null || idx >= cells.length) {
            return null;
        }
        String value = cells[idx].trim();
        return value.isEmpty() ? null : value;
    }

    private static BigDecimal parseRevenue(String raw) {
        if (raw == null || raw.isBlank()) {
            return BigDecimal.ZERO;
        }
        try {
            return new BigDecimal(raw.trim());
        } catch (NumberFormatException e) {
            // 金额坏不影响销量入库；日志足以定位，不必让整行失败
            return BigDecimal.ZERO;
        }
    }

    private static String stripCr(String line) {
        return line.endsWith("\r") ? line.substring(0, line.length() - 1) : line;
    }
}
