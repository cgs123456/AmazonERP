package com.amz.parse;

import lombok.Getter;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 结算原表（SP-API GET_V2_SETTLEMENT_REPORT_DATA_FLAT_FILE）TSV 解析器。
 * <p>
 * <b>按表头名定位列，不按列号硬编码</b>：结算原表的列在不同报告类型与站点上会增减，
 * 按列号取值会在平台调整列顺序时静默错位（把金额读成 SKU 之类），
 * 而且错得很安静 —— 数字仍是数字，只是含义变了。
 * <p>
 * <b>行级容错</b>：单行解析失败只记入 {@link ParseResult#getErrors()} 并跳过，
 * 不让一行脏数据丢掉整批结算 —— 一批几千行，整体失败意味着这批钱全部没入库。
 * 但表头缺必需列属于结构性问题，直接抛 {@link IllegalArgumentException}。
 */
public final class SettlementParser {

    public static final String COL_SETTLEMENT_ID = "settlement-id";
    public static final String COL_ORDER_ID = "order-id";
    public static final String COL_SKU = "sku";
    public static final String COL_TRANSACTION_TYPE = "transaction-type";
    public static final String COL_AMOUNT_TYPE = "amount-type";
    public static final String COL_AMOUNT = "amount";
    public static final String COL_CURRENCY = "currency";
    public static final String COL_DEPOSIT_DATE = "deposit-date";

    /** 缺任一列即无法落库，视为结构性错误。 */
    private static final List<String> REQUIRED_COLUMNS = List.of(
            COL_TRANSACTION_TYPE, COL_AMOUNT_TYPE, COL_AMOUNT);

    private SettlementParser() {
    }

    /**
     * 解析结算原表文本。
     *
     * @param content TSV 文本（首行表头）
     * @return 解析结果（成功行 + 行级错误）
     * @throws IllegalArgumentException 内容为空、无表头、或表头缺少必需列
     */
    public static ParseResult parse(String content) {
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("结算原表内容为空");
        }
        String[] lines = content.split("\r?\n");
        int firstData = -1;
        for (int i = 0; i < lines.length; i++) {
            if (!lines[i].isBlank()) {
                firstData = i;
                break;
            }
        }
        if (firstData < 0) {
            throw new IllegalArgumentException("结算原表内容为空");
        }

        String[] header = lines[firstData].split("\t", -1);
        Map<String, Integer> columnIndex = new LinkedHashMap<>();
        for (int i = 0; i < header.length; i++) {
            columnIndex.put(header[i].trim().toLowerCase(), i);
        }
        List<String> missing = new ArrayList<>();
        for (String required : REQUIRED_COLUMNS) {
            if (!columnIndex.containsKey(required)) {
                missing.add(required);
            }
        }
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException("结算原表缺少必需列：" + String.join(", ", missing)
                    + "（实际表头：" + String.join(", ", columnIndex.keySet()) + "）");
        }

        List<SettlementRow> rows = new ArrayList<>();
        List<RowError> errors = new ArrayList<>();
        int dataLines = 0;
        for (int i = firstData + 1; i < lines.length; i++) {
            String raw = lines[i];
            if (raw.isBlank()) {
                continue;
            }
            dataLines++;
            int lineNo = i + 1;
            String[] fields = raw.split("\t", -1);
            if (fields.length < header.length) {
                errors.add(new RowError(lineNo, "列数不足：期望 " + header.length + " 实际 " + fields.length, raw));
                continue;
            }
            String transactionType = value(fields, columnIndex, COL_TRANSACTION_TYPE);
            String amountType = value(fields, columnIndex, COL_AMOUNT_TYPE);
            String amountRaw = value(fields, columnIndex, COL_AMOUNT);
            if (transactionType == null || transactionType.isBlank()) {
                errors.add(new RowError(lineNo, "transaction-type 为空", raw));
                continue;
            }
            if (amountRaw == null || amountRaw.isBlank()) {
                errors.add(new RowError(lineNo, "amount 为空", raw));
                continue;
            }
            BigDecimal amount;
            try {
                amount = new BigDecimal(amountRaw.trim());
            } catch (NumberFormatException e) {
                errors.add(new RowError(lineNo, "amount 非数字：" + amountRaw, raw));
                continue;
            }

            SettlementRow row = new SettlementRow();
            row.setSettlementId(value(fields, columnIndex, COL_SETTLEMENT_ID));
            row.setOrderId(trimToNull(value(fields, columnIndex, COL_ORDER_ID)));
            row.setSku(trimToNull(value(fields, columnIndex, COL_SKU)));
            row.setTransactionType(transactionType.trim());
            row.setAmountType(amountType == null ? null : amountType.trim());
            row.setAmount(amount);
            row.setCurrency(value(fields, columnIndex, COL_CURRENCY));
            row.setDepositDate(value(fields, columnIndex, COL_DEPOSIT_DATE));
            row.setRowKey(rowKey(row));
            rows.add(row);
        }
        return new ParseResult(rows, errors, dataLines, new ArrayList<>(columnIndex.keySet()));
    }

    /**
     * 幂等指纹：同一行重复导入生成相同值。仅用业务字段，不含行号
     * （否则同一行文件内位移就会被当成新行）。
     */
    public static String rowKey(SettlementRow row) {
        String raw = nz(row.getSettlementId()) + "|" + nz(row.getOrderId()) + "|" + nz(row.getSku())
                + "|" + nz(row.getAmountType()) + "|"
                + (row.getAmount() == null ? "" : row.getAmount().stripTrailingZeros().toPlainString())
                + "|" + nz(row.getDepositDate());
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(32);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("MD5 not available", e);
        }
    }

    private static String value(String[] fields, Map<String, Integer> index, String column) {
        Integer i = index.get(column);
        if (i == null || i >= fields.length) {
            return null;
        }
        return fields[i];
    }

    private static String trimToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    /**
     * 构造行级错误（供落库阶段的失败记录复用同一类型）。
     *
     * @param lineNo  文件行号；落库阶段无行号时传 0
     * @param reason  失败原因
     * @param rawLine 原始行内容或标识（落库阶段传业务指纹）
     */
    public static RowError rowError(int lineNo, String reason, String rawLine) {
        return new RowError(lineNo, reason, rawLine);
    }

    /**
     * 解析结果。
     */
    @Getter
    public static final class ParseResult {
        private final List<SettlementRow> rows;
        private final List<RowError> errors;
        /** 参与解析的数据行数（不含表头与空行），= 成功行 + 错误行。 */
        private final int dataLineCount;
        /** 实际表头列名（用于口径排查）。 */
        private final List<String> headerColumns;

        ParseResult(List<SettlementRow> rows, List<RowError> errors, int dataLineCount,
                    List<String> headerColumns) {
            this.rows = rows;
            this.errors = errors;
            this.dataLineCount = dataLineCount;
            this.headerColumns = headerColumns;
        }
    }

    /**
     * 行级解析错误（保留行号与原始行，便于人工核对）。
     */
    @Getter
    public static final class RowError {
        private final int lineNo;
        private final String reason;
        private final String rawLine;

        RowError(int lineNo, String reason, String rawLine) {
            this.lineNo = lineNo;
            this.reason = reason;
            this.rawLine = rawLine;
        }
    }
}
