package com.amz.service.impl;

import com.amz.dto.ProductCsvImportReport;
import com.amz.model.AmzProduct;
import com.amz.service.ProductMasterService;
import com.amz.util.CsvImportSupport;
import com.amz.util.CsvImportSupport.Accumulator;
import com.amz.util.CsvImportSupport.RowOutcome;
import com.amz.util.CsvImportUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.math.BigDecimal;
import java.util.Map;

/**
 * 商品主数据 CSV 导入（A6）。
 *
 * <p>口径与单条 create/update 完全一致——复用同一套校验而不是另写一套，
 * 否则「批量导入能进、单条编辑进不去」的割裂会让使用者怀疑数据被悄悄改松。
 *
 * <p>幂等键是 (shopId, sku, marketplaceId)：已存在走更新，不存在走新建。
 * 这让同一份文件重复导入是安全的（第二次全部记 updated 而非新增重复行）。
 *
 * <p>行级隔离：一行校验失败只记这一行（带文件行号），不挡其余行。
 * 但「整行一个字段都没填」会被判为空行跳过并计入 skipped，不计入 failed——
 * 使用者文件末尾常带空行，把空行算失败会让「1 条失败」掩盖真实问题。
 */
@Slf4j
@Service
public class ProductCsvImportService {

    /** 表头必需列。缺任一即整批拒绝（结构错误），不做部分导入。 */
    public static final java.util.List<String> REQUIRED_COLUMNS =
            java.util.List.of("sku", "marketplace_id", "title");

    @Autowired
    private ProductMasterService productMasterService;

    public ProductCsvImportReport importCsv(Long shopId, BufferedReader reader) {
        CsvImportUtil.Parsed parsed = CsvImportUtil.parse(reader, REQUIRED_COLUMNS,
                CsvImportUtil.DEFAULT_MAX_ROWS);

        Accumulator acc = CsvImportSupport.run(parsed, (lineNumber, row) -> applyRow(shopId, row));

        ProductCsvImportReport report = new ProductCsvImportReport();
        report.setTotalRows(acc.totalRows);
        report.setHeaders(acc.headers);
        report.setInsertedRows(acc.insertedRows);
        report.setUpdatedRows(acc.updatedRows);
        report.setSkippedRows(acc.skippedRows);
        report.setErrors(acc.errors);
        report.setFailedRows(acc.failedRows());
        return report;
    }

    /** 单行：空行跳过，存在则更新、不存在则新建（幂等键 shopId+sku+marketplaceId）。 */
    private RowOutcome applyRow(Long shopId, Map<String, String> row) {
        AmzProduct product = toProduct(shopId, row);
        boolean blank = !hasText(product.getSku()) && !hasText(product.getTitle())
                && !hasText(product.getMarketplaceId());
        if (blank) {
            return RowOutcome.SKIPPED;
        }
        AmzProduct existing = findExisting(product);
        if (existing == null) {
            productMasterService.create(product);
            return RowOutcome.INSERTED;
        }
        productMasterService.update(shopId, existing.getId(), product);
        return RowOutcome.UPDATED;
    }

    /** 已存在判定：create 的唯一键是 (shopId, sku, marketplaceId)，这里用同一口径找。 */
    private AmzProduct findExisting(AmzProduct product) {
        if (!hasText(product.getSku()) || !hasText(product.getMarketplaceId())) {
            return null;
        }
        for (AmzProduct p : productMasterService.list(product.getShopId(), null, null)) {
            if (p.getSku() != null && p.getSku().equalsIgnoreCase(product.getSku().trim())
                    && p.getMarketplaceId() != null
                    && p.getMarketplaceId().equalsIgnoreCase(product.getMarketplaceId().trim())) {
                return p;
            }
        }
        return null;
    }

    /**
     * CSV 行 → 实体。字段名与模板表头一一对应（下划线转驼峰由调用方在表头里约定）。
     * <p>
     * 数值列（price / weight_g）解析失败不在这里吞掉——抛出去让行级 catch 记成
     * 「第 N 行：价格不是数字」，比静默置空更可操作。
     */
    AmzProduct toProduct(Long shopId, Map<String, String> row) {
        AmzProduct product = new AmzProduct();
        product.setShopId(shopId);
        product.setSku(trimToNull(row.get("sku")));
        product.setMarketplaceId(trimToNull(row.get("marketplace_id")));
        product.setTitle(trimToNull(row.get("title")));
        product.setAsin(trimToNull(row.get("asin")));
        product.setBrand(trimToNull(row.get("brand")));
        product.setCurrency(trimToNull(row.get("currency")));
        product.setCategory(trimToNull(row.get("category")));
        product.setProductType(trimToNull(row.get("product_type")));
        product.setSizeTier(trimToNull(row.get("size_tier")));
        product.setStatus(trimToNull(row.get("status")));
        String price = trimToNull(row.get("price"));
        if (price != null) {
            try {
                product.setPrice(new BigDecimal(price));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("价格不是合法数字：" + price);
            }
        }
        String weight = trimToNull(row.get("weight_g"));
        if (weight != null) {
            try {
                product.setWeightG(Integer.valueOf(weight));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("重量（克）不是整数：" + weight);
            }
        }
        return product;
    }

    private static String trimToNull(String v) {
        if (v == null) {
            return null;
        }
        String t = v.trim();
        return t.isEmpty() ? null : t;
    }

    private static boolean hasText(String v) {
        return v != null && !v.isBlank();
    }
}
