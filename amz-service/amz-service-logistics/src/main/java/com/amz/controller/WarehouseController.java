package com.amz.controller;

import com.amz.annotation.OperLog;
import com.amz.annotation.RequireRole;
import com.amz.annotation.ShopScoped;
import com.amz.dto.InventoryCsvImportReport;
import com.amz.model.Warehouse;
import com.amz.model.WarehouseInventory;
import com.amz.result.PageRequest;
import com.amz.result.Result;
import com.amz.service.WarehouseService;
import com.amz.service.impl.WarehouseInventoryCsvImportService;
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
 * 海外仓管理 REST 端点。
 */
@Slf4j
@RestController
@RequestMapping("/logistics/warehouse")
public class WarehouseController {

    @Autowired
    private WarehouseService warehouseService;

    @Autowired
    private WarehouseInventoryCsvImportService inventoryCsvImportService;

    /**
     * 创建仓库。
     * POST /logistics/warehouse
     */
    @RequireRole({"OPERATOR", "ADMIN"})
    @OperLog(module = "仓储", action = "新增", description = "创建仓库")
    @PostMapping
    public Result<Warehouse> create(@RequestBody Warehouse warehouse) {
        return Result.success(warehouseService.createWarehouse(warehouse));
    }

    /**
     * 更新仓库信息。
     * PUT /logistics/warehouse
     */
    @RequireRole({"OPERATOR", "ADMIN"})
    @OperLog(module = "仓储", action = "修改", description = "更新仓库信息")
    @PutMapping
    public Result<Warehouse> update(@RequestBody Warehouse warehouse) {
        return Result.success(warehouseService.updateWarehouse(warehouse));
    }

    /**
     * 查询店铺仓库列表。
     * GET /logistics/warehouse/list/{shopId}?warehouseType=
     */
    @ShopScoped
    @GetMapping("/list/{shopId}")
    public Result<List<Warehouse>> list(@PathVariable Long shopId,
                                        @RequestParam(required = false) String warehouseType) {
        return Result.success(warehouseService.listWarehouses(shopId, warehouseType));
    }

    /**
     * 查询仓库库存（按仓库 / SKU / 店铺筛选）。
     * GET /logistics/warehouse/inventory?warehouseId=&sku=&shopId=
     */
    @ShopScoped
    @GetMapping("/inventory")
    public Result<List<WarehouseInventory>> inventory(@RequestParam(required = false) Long warehouseId,
                                                       @RequestParam(required = false) String sku,
                                                       @RequestParam(required = false) Long shopId,
                                                       @RequestParam(required = false) Integer size,
                                                       @RequestParam(required = false) String cursor) {
        return Result.paged(warehouseService.listInventory(
                warehouseId, sku, shopId, PageRequest.of(size, cursor)));
    }

    /**
     * 更新库位码。
     * PUT /logistics/warehouse/inventory/{inventoryId}/location?locationCode=A-01-02
     */
    @RequireRole({"OPERATOR", "ADMIN"})
    @OperLog(module = "库存", action = "调整", description = "库位调整")
    @PutMapping("/inventory/{inventoryId}/location")
    public Result<WarehouseInventory> updateLocation(@PathVariable Long inventoryId,
                                                     @RequestParam String locationCode) {
        return Result.success(warehouseService.updateLocationCode(inventoryId, locationCode));
    }

    /**
     * CSV 批量维护库存元数据（A6）。
     * <p>
     * POST /logistics/warehouse/inventory/import/csv?shopId=1（multipart，字段名 file）
     * <p>
     * 模板必需列 warehouse_id / sku / batch_no（行定位键；batch_no 列必须在，值可空=匹配无批次行）。
     * 元数据列 asin / location_code / expire_date 非空才覆盖，留空保持原值。
     * <p>
     * 两条刻意限制（见 WarehouseInventoryCsvImportService 类注释）：
     * ① 只更新已存在的行，不新建——表上无唯一键，且库存行应由单据产生；
     * ② 数量类列出现在文件里即按行拒绝——库存变更必须走盘点/出入库单据进流水账。
     */
    @RequireRole({"OPERATOR", "ADMIN"})
    @ShopScoped
    @OperLog(module = "仓储", action = "CSV导入", description = "批量维护库存元数据")
    @PostMapping(value = "/inventory/import/csv", consumes = "multipart/form-data")
    public Result<InventoryCsvImportReport> importInventoryCsv(@RequestParam Long shopId,
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
            return Result.success(inventoryCsvImportService.importCsv(shopId,
                    new java.io.BufferedReader(new java.io.InputStreamReader(
                            file.getInputStream(), java.nio.charset.StandardCharsets.UTF_8))));
        } catch (CsvImportUtil.StructuralException e) {
            // 结构错误（缺列/空文件/超行数）：整批拒绝，不做部分导入
            return Result.failure(e.getMessage());
        } catch (Exception e) {
            log.warn("库存元数据 CSV 导入失败 shopId={}: {}", shopId, e.getMessage());
            return Result.failure("导入失败：" + e.getMessage());
        }
    }
}
