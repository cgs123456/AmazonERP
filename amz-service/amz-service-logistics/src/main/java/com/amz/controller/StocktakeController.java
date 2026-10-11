package com.amz.controller;

import com.amz.annotation.OperLog;
import com.amz.annotation.RequireRole;
import com.amz.annotation.ShopScoped;
import com.amz.model.Stocktake;
import com.amz.result.Result;
import com.amz.service.impl.StocktakeService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 库存盘点接口（账实核对）。
 * <p>
 * 流程：建单（快照账面）→ 录实盘 → 修正落账（按实盘 SET 库存 + 写 STOCKTAKE_ADJUST 流水）。
 * 修正语义见 {@link StocktakeService}：以 apply 时的真实账面为 before，未盘行跳过不按 0。
 */
@Slf4j
@RestController
@RequestMapping("/logistics/stocktake")
public class StocktakeController {

    @Autowired
    private StocktakeService stocktakeService;

    /**
     * 建单（快照账面数）。
     */
    @ShopScoped
    @RequireRole({"OPERATOR", "ADMIN"})
    @OperLog(module = "库存盘点", action = "新增", description = "创建盘点单并快照账面")
    @PostMapping
    public Result<Stocktake> create(@RequestBody CreateStocktakeRequest request) {
        try {
            return Result.success(stocktakeService.create(
                    request.getShopId(), request.getWarehouseId(), request.getRemark(), request.getSkus()));
        } catch (Exception e) {
            log.warn("创建盘点单失败: {}", e.getMessage());
            return Result.failure(e.getMessage());
        }
    }

    /**
     * 录入实盘（可反复修正，不影响账面）。
     */
    @RequireRole({"OPERATOR", "ADMIN"})
    @OperLog(module = "库存盘点", action = "录入", description = "录入实盘数量")
    @PostMapping("/{id}/count")
    public Result<Stocktake> count(@PathVariable Long id,
                                   @RequestBody List<StocktakeService.CountLine> counts) {
        try {
            return Result.success(stocktakeService.count(id, counts));
        } catch (Exception e) {
            log.warn("盘点录入失败 id={}: {}", id, e.getMessage());
            return Result.failure(e.getMessage());
        }
    }

    /**
     * 修正落账（COUNTED → APPLIED，按实盘 SET 库存 + 写流水）。
     */
    @RequireRole({"OPERATOR", "ADMIN"})
    @OperLog(module = "库存盘点", action = "落账", description = "按实盘修正库存并写流水")
    @PostMapping("/{id}/apply")
    public Result<Map<String, Object>> apply(@PathVariable Long id) {
        try {
            StocktakeService.ApplyResult result = stocktakeService.apply(id);
            return Result.success(Map.of(
                    "adjusted", result.adjusted(),
                    "skippedUncounted", result.skippedUncounted(),
                    "noDiff", result.noDiff()));
        } catch (Exception e) {
            log.warn("盘点落账失败 id={}: {}", id, e.getMessage());
            return Result.failure(e.getMessage());
        }
    }

    /**
     * 取消（DRAFT/COUNTED）。
     */
    @RequireRole({"OPERATOR", "ADMIN"})
    @OperLog(module = "库存盘点", action = "取消", description = "取消盘点单")
    @PostMapping("/{id}/cancel")
    public Result<Stocktake> cancel(@PathVariable Long id) {
        try {
            return Result.success(stocktakeService.cancel(id));
        } catch (Exception e) {
            log.warn("盘点取消失败 id={}: {}", id, e.getMessage());
            return Result.failure(e.getMessage());
        }
    }

    /**
     * 盘点单列表。
     */
    @ShopScoped
    @GetMapping("/list/{shopId}")
    public Result<List<Stocktake>> list(@PathVariable Long shopId,
                                        @RequestParam(required = false) String status) {
        try {
            return Result.success(stocktakeService.list(shopId, status));
        } catch (Exception e) {
            log.warn("查询盘点单失败 shopId={}: {}", shopId, e.getMessage());
            return Result.failure(e.getMessage());
        }
    }

    /**
     * 盘点单详情（含明细）。
     */
    @RequireRole({"VIEWER", "OPERATOR", "ADMIN"})
    @GetMapping("/{id}")
    public Result<StocktakeService.StocktakeDetail> detail(@PathVariable Long id) {
        try {
            return Result.success(stocktakeService.detail(id));
        } catch (Exception e) {
            log.warn("查询盘点详情失败 id={}: {}", id, e.getMessage());
            return Result.failure(e.getMessage());
        }
    }

    /** 建单请求体。 */
    public static class CreateStocktakeRequest {
        private Long shopId;
        private Long warehouseId;
        private String remark;
        private List<String> skus;

        public Long getShopId() {
            return shopId;
        }

        public void setShopId(Long shopId) {
            this.shopId = shopId;
        }

        public Long getWarehouseId() {
            return warehouseId;
        }

        public void setWarehouseId(Long warehouseId) {
            this.warehouseId = warehouseId;
        }

        public String getRemark() {
            return remark;
        }

        public void setRemark(String remark) {
            this.remark = remark;
        }

        public List<String> getSkus() {
            return skus;
        }

        public void setSkus(List<String> skus) {
            this.skus = skus;
        }
    }
}
