package com.amz.service.impl;

import com.amz.dto.InventoryCsvImportReport;
import com.amz.mapper.WarehouseInventoryMapper;
import com.amz.model.WarehouseInventory;
import com.amz.util.CsvImportSupport;
import com.amz.util.CsvImportSupport.Accumulator;
import com.amz.util.CsvImportSupport.RowOutcome;
import com.amz.util.CsvImportUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * 仓库库存元数据 CSV 批量维护（A6 第二个域）。
 *
 * <p><b>只改元数据（ASIN / 库位码 / 过期日期），不改数量。</b>这不是偷懒而是硬约束：
 * <ul>
 *   <li>{@code quantity / reserved_quantity / inbound_quantity} 受库存流水账（P1.1）管辖，
 *       任何改量都必须由单据（出入库/调拨/盘点/退货）带流水落账。CSV 直接 SET 数量
 *       等于把流水账绕过去——库存变了对不上账，正是 P1 立流水账要消灭的状态；</li>
 *   <li>{@code available_quantity} 是 STORED 生成列，写它无效。</li>
 * </ul>
 * 要按实盘调数量，走已存在的<b>盘点单</b>（{@code adjustQuantityTo + STOCKTAKE_ADJUST}）；
 * 要在出入库单上收货，走批量到货（P2.3）。CSV 里出现数量列时按行拒绝并指路，不静默忽略。
 *
 * <p><b>只更新已存在的行，不新建。</b>表上没有 (shop, warehouse, sku, batch) 唯一键
 * （只有普通索引），find-or-create 的「先查后插」在并发下会插出重复行；而「批量补元数据」
 * 的真实场景就是行已存在（系统先收单据生成库存行，再人工补 ASIN/库位）。定位不到行的
 * 那一行按失败报出，让使用者先建库存。
 *
 * <p><b>行定位不唯一即拒绝。</b>键值命中 0 行报「找不到」，命中多行报「数据不唯一」——
 * 绝不挑一行来更新，那是把猜错的风险转嫁给使用者且不留痕迹。
 */
@Slf4j
@Service
public class WarehouseInventoryCsvImportService {

    /** 模板必需列：行定位键。batch_no 列必须存在，但值可为空——空值表示「匹配无批次的行」。 */
    public static final List<String> REQUIRED_COLUMNS =
            List.of("warehouse_id", "sku", "batch_no");

    /**
     * 禁止经 CSV 批量修改的列：全部受流水账/单据管辖或为生成列。
     * 文件里出现这些列且有值时按行拒绝，指路到正确入口而不是静默丢弃那一列。
     */
    static final List<String> QUANTITY_COLUMNS =
            Arrays.asList("quantity", "available_quantity", "reserved_quantity", "inbound_quantity");

    /** 元数据列：非空才覆盖，留空 = 保持原值（与列缺失同语义）。 */
    private static final String COL_ASIN = "asin";
    private static final String COL_LOCATION = "location_code";
    private static final String COL_EXPIRE = "expire_date";

    @Autowired
    private WarehouseInventoryMapper inventoryMapper;

    public InventoryCsvImportReport importCsv(Long shopId, BufferedReader reader) {
        CsvImportUtil.Parsed parsed = CsvImportUtil.parse(reader, REQUIRED_COLUMNS,
                CsvImportUtil.DEFAULT_MAX_ROWS);

        Accumulator acc = CsvImportSupport.run(parsed, (lineNumber, row) -> applyRow(shopId, row));

        InventoryCsvImportReport report = new InventoryCsvImportReport();
        report.setTotalRows(acc.totalRows);
        report.setHeaders(acc.headers);
        report.setUpdatedRows(acc.updatedRows);
        report.setSkippedRows(acc.skippedRows);
        report.setErrors(acc.errors);
        report.setFailedRows(acc.failedRows());
        return report;
    }

    /**
     * 返回 UPDATED=改成功，SKIPPED=整行留空无改动；失败抛异常（原因带进 errors）。
     * 校验顺序刻意先挡「数量列」再定位：数量列一旦存在，整行语义就错，不用白扫一遍库。
     */
    RowOutcome applyRow(Long shopId, Map<String, String> row) {
        String quantityProblem = firstQuantityColumnWithText(row);
        if (quantityProblem != null) {
            throw new IllegalArgumentException("文件里的数量类列不能批量改（当前列：" + quantityProblem
                    + "）：改量请走盘点单或出入库单据，库存变更要进流水账");
        }

        Long warehouseId = parseLong(trimToNull(row.get("warehouse_id")));
        if (warehouseId == null) {
            throw new IllegalArgumentException("warehouse_id 缺失或不是整数");
        }
        String sku = trimToNull(row.get("sku"));
        if (sku == null) {
            throw new IllegalArgumentException("sku 不能为空");
        }
        String batch = trimToNull(row.get("batch_no"));

        List<WarehouseInventory> hits = inventoryMapper.selectList(
                new LambdaQueryWrapper<WarehouseInventory>()
                        .eq(WarehouseInventory::getShopId, shopId)
                        .eq(WarehouseInventory::getWarehouseId, warehouseId)
                        .eq(WarehouseInventory::getSku, sku)
                        .apply(batch == null, "batch_no IS NULL")
                        .eq(batch != null, WarehouseInventory::getBatchNo, batch));

        if (hits.isEmpty()) {
            throw new IllegalArgumentException("找不到该库存行（warehouse_id=" + warehouseId + " sku=" + sku
                    + (batch == null ? " 无批次" : " 批次=" + batch) + "）：CSV 只补元数据，库存行请先由单据产生");
        }
        if (hits.size() > 1) {
            // 表无唯一键，脏数据可能本来就有多行；这里绝不替使用者挑一行
            throw new IllegalArgumentException("该键值命中 " + hits.size()
                    + " 行（数据不唯一，表上无唯一约束），为避免改错行不更新：请先人工合并重复库存行");
        }

        WarehouseInventory target = hits.get(0);
        String asin = pick(row, COL_ASIN, target.getAsin(), 20, "ASIN");
        String location = pick(row, COL_LOCATION, target.getLocationCode(),
                WarehouseServiceImpl.LOCATION_CODE_MAX_LENGTH, "库位码");
        String expireText = trimToNull(row.get(COL_EXPIRE));
        LocalDate expire;
        try {
            expire = expireText == null ? target.getExpireDate() : LocalDate.parse(expireText);
        } catch (Exception e) {
            throw new IllegalArgumentException("过期日期格式应为 yyyy-MM-dd，实际：" + expireText);
        }
        // 全空（都保持原值）时不写库：避免无意义 update_time 抖动，也让「空行」统计准确
        boolean changed = !equalsNullSafe(asin, target.getAsin())
                || !equalsNullSafe(location, target.getLocationCode())
                || (expire != null && !expire.equals(target.getExpireDate()));
        if (!changed) {
            return RowOutcome.SKIPPED;
        }
        int affected = inventoryMapper.updateMetadataById(
                target.getId(), shopId, asin, location, expire);
        if (affected == 0) {
            throw new IllegalArgumentException("行在提交前被删除或未更新（并发），请重查后重试");
        }
        return RowOutcome.UPDATED;
    }

    /** 取列值：非空用文件值（并校验长度），空=保持原值。 */
    private String pick(Map<String, String> row, String column, String original, int maxLength, String label) {
        String v = trimToNull(row.get(column));
        if (v == null) {
            return original;
        }
        if (v.length() > maxLength) {
            throw new IllegalArgumentException(label + "最长 " + maxLength + " 字符，实际 " + v.length());
        }
        return v;
    }

    /** 文件里出现的第一个「有值的数量类列」名；没有则 null。 */
    static String firstQuantityColumnWithText(Map<String, String> row) {
        for (String col : QUANTITY_COLUMNS) {
            String v = trimToNull(row.get(col));
            if (v != null) {
                return col;
            }
        }
        return null;
    }

    private static Long parseLong(String v) {
        if (v == null) {
            return null;
        }
        try {
            return Long.valueOf(v);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String trimToNull(String v) {
        if (v == null) {
            return null;
        }
        String t = v.trim();
        return t.isEmpty() ? null : t;
    }

    private static boolean equalsNullSafe(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }
}
