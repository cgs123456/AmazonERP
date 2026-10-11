package com.amz.controller;
import com.amz.annotation.OperLog;
import com.amz.annotation.RequireRole;
import com.amz.annotation.ShopScoped;

import com.amz.connector.ErrorSummary;
import com.amz.connector.LocalApiException;
import com.amz.mapper.FbaInventoryMapper;
import com.amz.model.FbaInventory;
import com.amz.result.Result;
import com.amz.scheduler.InventorySyncScheduler;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.context.annotation.Profile;

import java.util.List;

/**
 * FBA 库存同步对外接口。
 * <p>
 * 提供库存健康度查询与手动触发同步两类能力。
 * <p>
 * 类级映射使用 {@code /spapi/inventory} 而非 {@code /inventory}：网关仅配置了
 * {@code Path=/spapi/**} 路由到本服务，裸 {@code /inventory} 前缀外部不可达。
 * 本类的 {@code /health/{shopId}}（路径变量）与 {@link SpapiController} 的
 * {@code /inventory/health}（查询参数）为不同签名的两个端点，Spring 按字面量优先
 * 匹配，不构成 Ambiguous mapping。
 */
@Profile("!bootstrap")
@RestController
@RequestMapping("/spapi/inventory")
public class InventoryController {

    private static final Logger log = LoggerFactory.getLogger(InventoryController.class);

    @Autowired
    private FbaInventoryMapper fbaInventoryMapper;

    @Autowired
    private InventorySyncScheduler inventorySyncScheduler;

    /**
     * 查询指定店铺所有 SKU 的库存健康度列表。
     *
     * @param shopId 店铺 ID
     * @return 库存健康度列表
     */
    /**
     * FBA 库存健康 CSV 导出（P1.5）：与列表同口径（本店全量，无分页——健康度列表
     * 本身以单店为界，量级受 SKU 数约束）。
     */
    @ShopScoped
    @OperLog(module = "库存导出", action = "导出", description = "导出 FBA 库存健康 CSV")
    @GetMapping(value = "/health/{shopId}/export", produces = "text/csv; charset=UTF-8")
    public void exportHealth(@PathVariable Long shopId,
                             jakarta.servlet.http.HttpServletResponse response) throws java.io.IOException {
        if (shopId == null) {
            response.sendError(400, "shopId must not be null");
            return;
        }
        List<FbaInventory> list = fbaInventoryMapper.selectList(
                new LambdaQueryWrapper<FbaInventory>()
                        .eq(FbaInventory::getShopId, shopId)
                        .orderByAsc(FbaInventory::getSku));
        java.io.PrintWriter writer = com.amz.util.CsvExportUtil.begin(
                response, "inventory-health-" + shopId + ".csv");
        try {
            com.amz.util.CsvExportUtil.row(writer, java.util.List.of(
                    "SKU", "ASIN", "FNSKU", "商品名", "可售", "站点",
                    "7天日均", "30天日均", "可售天数", "健康状态"));
            for (FbaInventory inv : list) {
                com.amz.util.CsvExportUtil.row(writer, java.util.List.of(
                        inv.getSku(), inv.getAsin(), inv.getFnSku(), inv.getProductName(),
                        inv.getAvailableQuantity(), inv.getMarketplaceId(),
                        inv.getAvg7Days(), inv.getAvg30Days(),
                        inv.getDaysOfSupply(), inv.getHealthStatus()));
            }
        } finally {
            writer.flush();
        }
    }

    @ShopScoped
    @GetMapping("/health/{shopId}")
    public Result<List<FbaInventory>> health(@PathVariable Long shopId) {
        if (shopId == null) {
            return Result.failure("shopId must not be null",
                    ErrorSummary.localError(LocalApiException.CODE_INVALID_REQUEST));
        }
        List<FbaInventory> list = fbaInventoryMapper.selectList(
                new LambdaQueryWrapper<FbaInventory>()
                        .eq(FbaInventory::getShopId, shopId)
                        .orderByAsc(FbaInventory::getHealthStatus));
        return Result.success(list);
    }

    /**
     * 手动触发指定店铺的 FBA 库存同步。
     *
     * @param shopId 店铺 ID
     * @return 本次同步落库的 SKU 数
     */
    @ShopScoped
    @PostMapping("/sync/{shopId}")
    public Result<Integer> sync(@PathVariable Long shopId) {
        if (shopId == null) {
            return Result.failure("shopId must not be null",
                    ErrorSummary.localError(LocalApiException.CODE_INVALID_REQUEST));
        }
        try {
            int synced = inventorySyncScheduler.syncShopInventory(shopId);
            log.info("manual sync inventory shopId={} count={}", shopId, synced);
            return Result.success(synced);
        } catch (Exception e) {
            log.error("manual sync inventory failed shopId={}", shopId, e);
            return Result.failure("sync failed: " + ErrorSummary.of(e), ErrorSummary.toApiError(e));
        }
    }
}
