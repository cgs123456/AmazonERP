package com.amz.controller;

import com.amz.annotation.OperLog;
import com.amz.annotation.RequireRole;
import com.amz.annotation.ShopScoped;
import com.amz.context.UserContext;
import com.amz.dto.ProductCsvImportReport;
import com.amz.model.AmzProduct;
import com.amz.result.Result;
import com.amz.service.ProductMasterService;
import com.amz.service.impl.ProductCsvImportService;
import com.amz.util.CsvImportUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 商品主数据端点。
 * <p>
 * 2026-10-02 功能覆盖清点新增：{@code AmzProduct}（含 brand / sizeTier / weightG / marketplaceId）
 * 此前只有 ListingCopyService 在读，没有任何写入与查询入口，导致 FBA 费用试算只能靠临时传参、
 * 跨店铺的同一 ASIN 映射无从查起。旧的 {@code /product/getProductList} 一族走
 * {@code @Deprecated} 的 12 字段实体，保持不动，新页面走本控制器。
 */
@Slf4j
@RestController
@RequestMapping("/product/master")
public class ProductMasterController {

    @Autowired
    private ProductMasterService productMasterService;

    @Autowired
    private ProductCsvImportService productCsvImportService;

    @ShopScoped
    @GetMapping("/list/{shopId}")
    public Result<List<AmzProduct>> list(@PathVariable Long shopId,
                                         @RequestParam(required = false) String asin,
                                         @RequestParam(required = false) String keyword) {
        try {
            return Result.success(productMasterService.list(shopId, asin, keyword));
        } catch (IllegalArgumentException e) {
            return Result.failure(e.getMessage());
        }
    }

    @ShopScoped
    @GetMapping("/{shopId}/{id}")
    public Result<AmzProduct> get(@PathVariable Long shopId, @PathVariable Long id) {
        try {
            return Result.success(productMasterService.get(shopId, id));
        } catch (IllegalArgumentException e) {
            return Result.failure(e.getMessage());
        }
    }

    /**
     * CSV 批量导入商品主数据（A6）。
     * <p>
     * POST /product/master/import/csv?shopId=1（multipart，字段名 file）
     * <p>
     * 模板表头（按名定位，使用者可增列不影响）：
     * {@code sku, marketplace_id, title} 为必需列，缺任一整批拒绝并点名；
     * 其余可选：asin / brand / price / currency / category / product_type /
     * size_tier / weight_g / status。
     * <p>
     * 幂等键 (shopId, sku, marketplaceId)：已存在走更新，不存在走新建，
     * 因此同一份文件重复导入是安全的。报告带回显表头与行级错误（带文件行号）。
     * <p>
     * 大小与类型在控制器就挡掉：超限/非 CSV 直接拒绝，不进入解析。
     */
    @RequireRole({"OPERATOR", "ADMIN"})
    @ShopScoped
    @OperLog(module = "商品主数据", action = "CSV导入", description = "批量导入商品主数据")
    @PostMapping(value = "/import/csv", consumes = "multipart/form-data")
    public Result<ProductCsvImportReport> importCsv(@RequestParam Long shopId,
                                                    @RequestParam("file") MultipartFile file) {
        if (file == null || file.isEmpty()) {
            return Result.failure("上传文件为空");
        }
        if (file.getSize() > CsvImportUtil.DEFAULT_MAX_BYTES) {
            return Result.failure("文件超过大小上限 "
                    + (CsvImportUtil.DEFAULT_MAX_BYTES / 1024 / 1024) + "MB，当前 "
                    + (file.getSize() / 1024 / 1024) + "MB");
        }
        String name = file.getOriginalFilename();
        if (name == null || !name.toLowerCase().endsWith(".csv")) {
            return Result.failure("只支持 .csv 文件（Excel 请另存为 CSV UTF-8）");
        }
        try {
            return Result.success(productCsvImportService.importCsv(shopId,
                    new java.io.BufferedReader(new java.io.InputStreamReader(
                            file.getInputStream(), java.nio.charset.StandardCharsets.UTF_8))));
        } catch (CsvImportUtil.StructuralException e) {
            // 结构错误（缺列/空文件/超行数）：整批拒绝，不做部分导入
            return Result.failure(e.getMessage());
        } catch (Exception e) {
            log.warn("商品 CSV 导入失败 shopId={}: {}", shopId, e.getMessage());
            return Result.failure("导入失败：" + e.getMessage());
        }
    }

    @ShopScoped
    @PostMapping
    public Result<AmzProduct> create(@RequestBody AmzProduct product) {
        try {
            return Result.success(productMasterService.create(product));
        } catch (IllegalArgumentException e) {
            return Result.failure(e.getMessage());
        }
    }

    @ShopScoped
    @PutMapping("/{shopId}/{id}")
    public Result<AmzProduct> update(@PathVariable Long shopId,
                                     @PathVariable Long id,
                                     @RequestBody AmzProduct patch) {
        try {
            return Result.success(productMasterService.update(shopId, id, patch));
        } catch (IllegalArgumentException e) {
            return Result.failure(e.getMessage());
        }
    }

    /**
     * 同一 ASIN 在"我管的店铺"里的登记情况。店铺集合取登录态里的授权列表，
     * 不接受调用方传 shopIds，否则就变成越权查询入口。
     */
    @GetMapping("/by-asin")
    public Result<List<AmzProduct>> byAsin(@RequestParam String asin) {
        List<Long> shops = UserContext.getShops();
        if (shops == null || shops.isEmpty()) {
            return Result.failure("当前账号没有可访问的店铺");
        }
        try {
            return Result.success(productMasterService.listByAsinAcrossShops(shops, asin));
        } catch (IllegalArgumentException e) {
            return Result.failure(e.getMessage());
        }
    }
}
