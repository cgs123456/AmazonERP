package com.amz.service.impl;

import com.amz.dto.SupplierPriceCsvImportReport;
import com.amz.mapper.SupplierProductMapper;
import com.amz.model.SupplierProduct;
import com.amz.util.CsvImportSupport;
import com.amz.util.CsvImportSupport.Accumulator;
import com.amz.util.CsvImportSupport.RowOutcome;
import com.amz.util.CsvImportUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * 供货价 CSV 批量维护（A6 第三个域）。
 *
 * <p>场景真实：供应商定期发来一张修订价目表（供货价 / MOQ / 交期），逐条手改成本高。
 * 这是系统里第三个 CSV 导入域，也是抽 {@link CsvImportSupport} 的时机——前两个域
 * 已把同一段行循环写了两遍，重复发生才抽，不是提前抽象。
 *
 * <p><b>身份靠请求参数、不靠表内 ID。</b>supplierId 由界面选定（选哪个供应商、传它的价目表），
 * 每行只填 sku 定位；不把内部 supplier_id 塞进 Excel 让使用者手抄。
 *
 * <p>与库存元数据导入<b>同形但不同契约</b>：
 * <ul>
 *   <li>只更新已存在行、不新建——表上无 (shop, supplier, sku) 唯一键（只有索引），
 *       先查后插并发会插重复行；且本域没有单条更新端点可复用，新建走的是纯 insert 的
 *       {@code addSupplierProduct}，语义是「新增供货关系」而非「改价」，不能拿来当 upsert；</li>
 *   <li>拒绝 {@code is_preferred}：设首选有<b>跨行副作用</b>（要先清同 SKU 其他首选），
 *       放进逐行循环里做跨行改写既慢又易错，按行拒绝并指路到单条表单；</li>
 *   <li>没有库存那种「数量必走流水账」硬约束（供货价是普通字段），但同样按列 UPDATE、
 *       留空保持原值。</li>
 * </ul>
 */
@Slf4j
@Service
public class SupplierPriceCsvImportService {

    /** 模板必需列：行定位键（sku）。供应商与店铺由请求参数定，不进表。 */
    public static final List<String> REQUIRED_COLUMNS = List.of("sku");

    /**
     * 禁止经本路径改的列：身份列（不是"价格"、且是定位键）、状态列（由单据流转维护）、
     * is_preferred（跨行副作用）。出现且有值即按行拒绝并指路，不静默丢列。
     */
    static final List<String> FORBIDDEN_COLUMNS =
            Arrays.asList("supplier_id", "shop_id", "status", "is_preferred");

    @Autowired
    private SupplierProductMapper supplierProductMapper;

    public SupplierPriceCsvImportReport importCsv(Long shopId, Long supplierId, BufferedReader reader) {
        if (shopId == null || supplierId == null) {
            throw new IllegalArgumentException("shopId / supplierId 不能为空");
        }
        CsvImportUtil.Parsed parsed = CsvImportUtil.parse(reader, REQUIRED_COLUMNS,
                CsvImportUtil.DEFAULT_MAX_ROWS);

        // RowHandler 是 (lineNumber, row) 两参；本域的失败原因靠抛异常带出，
        // 行号由 CsvImportSupport 统一归集，这里不用单独接。
        Accumulator acc = CsvImportSupport.run(parsed, (lineNumber, row) -> applyRow(shopId, supplierId, row));

        SupplierPriceCsvImportReport report = new SupplierPriceCsvImportReport();
        report.setTotalRows(acc.totalRows);
        report.setUpdatedRows(acc.updatedRows);
        report.setSkippedRows(acc.skippedRows);
        report.setHeaders(acc.headers);
        report.setErrors(acc.errors);
        report.setFailedRows(acc.failedRows());
        return report;
    }

    private RowOutcome applyRow(Long shopId, Long supplierId, Map<String, String> row) {
        String forbidden = firstForbiddenColumnWithText(row);
        if (forbidden != null) {
            throw new IllegalArgumentException("「" + forbidden + "」列不能经批量改价维护"
                    + "（is_preferred 有跨行副作用、身份/状态不是价格——请走单条表单）");
        }

        String sku = trimToNull(row.get("sku"));
        if (sku == null) {
            throw new IllegalArgumentException("sku 不能为空");
        }

        List<SupplierProduct> hits = supplierProductMapper.selectList(
                new LambdaQueryWrapper<SupplierProduct>()
                        .eq(SupplierProduct::getShopId, shopId)
                        .eq(SupplierProduct::getSupplierId, supplierId)
                        .eq(SupplierProduct::getSku, sku));
        if (hits.isEmpty()) {
            throw new IllegalArgumentException("找不到该 SKU 的供货关系（sku=" + sku
                    + "）：批量只改价，供货关系请先在采购页建立");
        }
        if (hits.size() > 1) {
            throw new IllegalArgumentException("该键值命中 " + hits.size()
                    + " 行（表上无唯一约束，数据不唯一），为避免改错行不更新：请先人工合并重复供货关系");
        }

        SupplierProduct target = hits.get(0);
        SupplierProduct patch = merge(shopId, supplierId, sku, target, row);

        // 全留空（都保持原值）不写库：避免无意义 update_time 抖动，也让「空行」统计准确
        if (!changed(patch, target)) {
            return RowOutcome.SKIPPED;
        }
        int affected = supplierProductMapper.updatePriceById(patch);
        if (affected == 0) {
            throw new IllegalArgumentException("行在提交前被删除或未更新（并发），请重查后重试");
        }
        return RowOutcome.UPDATED;
    }

    /** 文件非空即覆盖，留空回填目标原值（与列缺失同语义）。数值/日期列格式错按行抛出。 */
    private SupplierProduct merge(Long shopId, Long supplierId, String sku,
                                  SupplierProduct target, Map<String, String> row) {
        SupplierProduct sp = new SupplierProduct();
        sp.setId(target.getId());
        sp.setShopId(shopId);
        sp.setSupplierId(supplierId);
        sp.setSku(sku);
        sp.setSupplierSkuCode(pick(row, "supplier_sku_code", target.getSupplierSkuCode(), 64, "供方 SKU"));
        sp.setSupplierOfferId(pick(row, "supplier_offer_id", target.getSupplierOfferId(), 64, "offer"));
        sp.setPackagingSpec(pick(row, "packaging_spec", target.getPackagingSpec(), 200, "包装规格"));
        sp.setSupplyPrice(decimal(row, "supply_price", target.getSupplyPrice()));
        sp.setUnitWeight(decimal(row, "unit_weight", target.getUnitWeight()));
        sp.setUnitVolume(decimal(row, "unit_volume", target.getUnitVolume()));
        sp.setMoq(integer(row, "moq", target.getMoq(), "起订量"));
        sp.setLeadTimeDays(integer(row, "lead_time_days", target.getLeadTimeDays(), "交期(天)"));
        return sp;
    }

    private static boolean changed(SupplierProduct p, SupplierProduct t) {
        return notEq(p.getSupplierSkuCode(), t.getSupplierSkuCode())
                || notEq(p.getSupplierOfferId(), t.getSupplierOfferId())
                || notEq(p.getPackagingSpec(), t.getPackagingSpec())
                || notEqBd(p.getSupplyPrice(), t.getSupplyPrice())
                || notEqBd(p.getUnitWeight(), t.getUnitWeight())
                || notEqBd(p.getUnitVolume(), t.getUnitVolume())
                || notEqI(p.getMoq(), t.getMoq())
                || notEqI(p.getLeadTimeDays(), t.getLeadTimeDays());
    }

    static String firstForbiddenColumnWithText(Map<String, String> row) {
        for (String col : FORBIDDEN_COLUMNS) {
            if (trimToNull(row.get(col)) != null) {
                return col;
            }
        }
        return null;
    }

    private String pick(Map<String, String> row, String col, String original, int max, String label) {
        String v = trimToNull(row.get(col));
        if (v == null) {
            return original;
        }
        if (v.length() > max) {
            throw new IllegalArgumentException(label + "最长 " + max + " 字符，实际 " + v.length());
        }
        return v;
    }

    private BigDecimal decimal(Map<String, String> row, String col, BigDecimal original) {
        String v = trimToNull(row.get(col));
        if (v == null) {
            return original;
        }
        try {
            BigDecimal n = new BigDecimal(v);
            if (n.signum() < 0) {
                throw new IllegalArgumentException(col + " 不能为负：" + v);
            }
            return n;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(col + " 不是合法数字：" + v);
        }
    }

    private Integer integer(Map<String, String> row, String col, Integer original, String label) {
        String v = trimToNull(row.get(col));
        if (v == null) {
            return original;
        }
        try {
            int n = Integer.parseInt(v);
            if (n < 0) {
                throw new IllegalArgumentException(label + "不能为负：" + v);
            }
            return n;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(label + "不是整数：" + v);
        }
    }

    private static String trimToNull(String v) {
        if (v == null) {
            return null;
        }
        String t = v.trim();
        return t.isEmpty() ? null : t;
    }

    private static boolean notEq(String a, String b) {
        return a == null ? b != null : !a.equals(b);
    }

    private static boolean notEqI(Integer a, Integer b) {
        return a == null ? b != null : !a.equals(b);
    }

    private static boolean notEqBd(BigDecimal a, BigDecimal b) {
        return a == null ? b != null : a.compareTo(b) != 0;
    }
}
