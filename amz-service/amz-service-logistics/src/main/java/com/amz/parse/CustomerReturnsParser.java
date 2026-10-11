package com.amz.parse;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * FBA 客户退货报表解析器（{@code GET_FBA_FULFILLMENT_CUSTOMER_RETURNS_DATA}）。
 * <p>
 * <b>报表列（官方 13 列）</b>：{@code return-date / order-id / sku / asin / fnsku /
 * product-name / quantity / fulfillment-center-id / detailed-disposition / reason /
 * status / license-plate-number / customer-comments}。
 * <p>
 * <b>口径与 SettlementParser 一致</b>：按<b>表头名</b>定位列，不按列号——
 * 官方列序会随报表版本调整，按列号取值会在平台调整顺序时静默错位（数字仍是数字，
 * 含义却变了）。必需列缺失即抛结构性错误。
 * <p>
 * <b>处置映射只映射能确证的</b>：官方 {@code detailed-disposition} 的取值集合
 * 未在本仓库的官方快照中给出（快照里 reportType 无 enum），因此这里**只认一个
 * 明确可售的值 {@code SELLABLE}**，其余一律落到 {@code DEFECTIVE}——
 * 宁可把不确定的当成不可售（不虚增可售库存），也不要把残次品算进补货建议。
 * 这个方向与「字段权限宁可多藏」「成本缺失不按 0 计」是同一条保守原则。
 * <p>
 * <b>状态映射</b>：官方 {@code status} 描述退货处理进展（如
 * {@code Unit returned to inventory} / {@code Reimbursed}）。本解析器只做
 * 「是否已回到可售库存」的粗判，不据此推进退货单状态机（状态机由收货动作推进，
 * 报表导入只建 PENDING 单）——把平台的文本状态直接映射成本地状态机状态，
 * 会在两边语义漂移时产生错状态。
 */
public final class CustomerReturnsParser {

    /** 报表类型（官方）。 */
    public static final String REPORT_TYPE = "GET_FBA_FULFILLMENT_CUSTOMER_RETURNS_DATA";

    public static final String COL_RETURN_DATE = "return-date";
    public static final String COL_ORDER_ID = "order-id";
    public static final String COL_SKU = "sku";
    public static final String COL_ASIN = "asin";
    public static final String COL_PRODUCT_NAME = "product-name";
    public static final String COL_QUANTITY = "quantity";
    public static final String COL_DISPOSITION = "detailed-disposition";
    public static final String COL_REASON = "reason";
    public static final String COL_STATUS = "status";

    /** 缺任一列即无法建单（sku/quantity 是退货单必填；order-id 用于关联订单）。 */
    private static final List<String> REQUIRED_COLUMNS = List.of(
            COL_ORDER_ID, COL_SKU, COL_QUANTITY, COL_RETURN_DATE);

    /** 唯一可确证「可再售」的官方处置值。其余一律按不可售处理。 */
    private static final String DISPOSITION_SELLABLE = "SELLABLE";

    private CustomerReturnsParser() {
    }

    /**
     * 解析退货报表文本。
     *
     * @param content TSV 文本（首行表头）
     * @return 解析结果（成功行 + 行级错误）
     * @throws IllegalArgumentException 内容为空、无表头、或表头缺少必需列
     */
    public static ParseResult parse(String content) {
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("退货报表内容为空");
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
            throw new IllegalArgumentException("退货报表内容为空");
        }

        String[] header = lines[firstData].split("\t", -1);
        Map<String, Integer> columnIndex = new LinkedHashMap<>();
        for (int i = 0; i < header.length; i++) {
            columnIndex.put(header[i].trim().toLowerCase(Locale.ROOT), i);
        }
        List<String> missing = new ArrayList<>();
        for (String required : REQUIRED_COLUMNS) {
            if (!columnIndex.containsKey(required)) {
                missing.add(required);
            }
        }
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException("退货报表缺少必需列：" + String.join(", ", missing)
                    + "（实际表头：" + String.join(", ", columnIndex.keySet()) + "）");
        }

        List<Row> rows = new ArrayList<>();
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
            String orderId = value(fields, columnIndex, COL_ORDER_ID);
            String sku = value(fields, columnIndex, COL_SKU);
            String quantityRaw = value(fields, columnIndex, COL_QUANTITY);
            String returnDate = value(fields, columnIndex, COL_RETURN_DATE);
            if (isBlank(orderId)) {
                errors.add(new RowError(lineNo, "order-id 为空", raw));
                continue;
            }
            if (isBlank(sku)) {
                errors.add(new RowError(lineNo, "sku 为空", raw));
                continue;
            }
            if (isBlank(returnDate)) {
                errors.add(new RowError(lineNo, "return-date 为空（幂等键需要它）", raw));
                continue;
            }
            int quantity;
            try {
                quantity = Integer.parseInt(quantityRaw == null ? "" : quantityRaw.trim());
            } catch (NumberFormatException e) {
                errors.add(new RowError(lineNo, "quantity 非整数：" + quantityRaw, raw));
                continue;
            }
            if (quantity <= 0) {
                errors.add(new RowError(lineNo, "quantity 必须为正：" + quantityRaw, raw));
                continue;
            }

            Row row = new Row();
            row.setOrderId(orderId.trim());
            row.setSku(sku.trim());
            row.setAsin(trimToNull(value(fields, columnIndex, COL_ASIN)));
            row.setProductName(trimToNull(value(fields, columnIndex, COL_PRODUCT_NAME)));
            row.setReturnDate(returnDate.trim());
            row.setQuantity(quantity);
            row.setReason(trimToNull(value(fields, columnIndex, COL_REASON)));
            row.setStatus(trimToNull(value(fields, columnIndex, COL_STATUS)));
            row.setDisposition(mapDisposition(value(fields, columnIndex, COL_DISPOSITION)));
            row.setExternalKey(externalKey(row));
            rows.add(row);
        }
        return new ParseResult(rows, errors, dataLines, new ArrayList<>(columnIndex.keySet()));
    }

    /**
     * 处置映射：只有 {@code SELLABLE} 判为良品（可回补可售库存），其余一律 DEFECTIVE。
     * <p>
     * 保守方向的理由：本仓库没有官方 disposition 枚举快照，无法确证其它取值的可售性；
     * 把不确定值判为可售会让残次品进补货建议（虚增库存），而判为不可售只是让运营
     * 在收货时手工改判——代价小得多。
     */
    static String mapDisposition(String raw) {
        if (raw != null && DISPOSITION_SELLABLE.equalsIgnoreCase(raw.trim())) {
            return "GOOD";
        }
        return "DEFECTIVE";
    }

    /**
     * 幂等键：店铺不参与（列上另有 shop_id），此处只做「来源 + 业务指纹」，
     * 由落库层拼上 shopId 后写 external_key。
     * <p>
     * 指纹取 {@code return-date|order-id|sku}：同一订单同一 SKU 的退货在报表里
     * 按退货日期分行，三者组合稳定。刻意不含 quantity/disposition/status——
     * 它们会随 Amazon 处理进展变化，进指纹会把「同一笔退货的更新」当成新行。
     */
    static String externalKey(Row row) {
        String basis = "FBA|" + row.getReturnDate() + "|" + row.getOrderId() + "|" + row.getSku();
        return md5Hex(basis);
    }

    private static String md5Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("MD5 不可用", e);
        }
    }

    private static String value(String[] fields, Map<String, Integer> columnIndex, String column) {
        Integer idx = columnIndex.get(column);
        if (idx == null || idx >= fields.length) {
            return null;
        }
        return fields[idx];
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /** 退货报表一行。 */
    public static class Row {
        private String orderId;
        private String sku;
        private String asin;
        private String productName;
        private String returnDate;
        private int quantity;
        private String disposition;
        private String reason;
        private String status;
        private String externalKey;

        public String getOrderId() {
            return orderId;
        }

        public void setOrderId(String orderId) {
            this.orderId = orderId;
        }

        public String getSku() {
            return sku;
        }

        public void setSku(String sku) {
            this.sku = sku;
        }

        public String getAsin() {
            return asin;
        }

        public void setAsin(String asin) {
            this.asin = asin;
        }

        public String getProductName() {
            return productName;
        }

        public void setProductName(String productName) {
            this.productName = productName;
        }

        public String getReturnDate() {
            return returnDate;
        }

        public void setReturnDate(String returnDate) {
            this.returnDate = returnDate;
        }

        public int getQuantity() {
            return quantity;
        }

        public void setQuantity(int quantity) {
            this.quantity = quantity;
        }

        /** GOOD / DEFECTIVE（映射自官方 detailed-disposition）。 */
        public String getDisposition() {
            return disposition;
        }

        public void setDisposition(String disposition) {
            this.disposition = disposition;
        }

        public String getReason() {
            return reason;
        }

        public void setReason(String reason) {
            this.reason = reason;
        }

        public String getStatus() {
            return status;
        }

        public void setStatus(String status) {
            this.status = status;
        }

        public String getExternalKey() {
            return externalKey;
        }

        public void setExternalKey(String externalKey) {
            this.externalKey = externalKey;
        }
    }

    /** 行级错误（带文件行号，供人工核对）。 */
    public record RowError(int lineNo, String message, String rawLine) {
    }

    /** 解析结果。 */
    public record ParseResult(List<Row> rows, List<RowError> errors, int dataLines,
                              List<String> columns) {
        public boolean hasStructuralError() {
            return errors.size() == dataLines && dataLines > 0;
        }
    }
}
