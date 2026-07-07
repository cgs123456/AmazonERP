package com.amz.controller;

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

import java.util.List;

/**
 * FBA 库存同步对外接口。
 * <p>
 * 提供库存健康度查询与手动触发同步两类能力。
 */
@RestController
@RequestMapping("/inventory")
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
    @GetMapping("/health/{shopId}")
    public Result<List<FbaInventory>> health(@PathVariable Long shopId) {
        if (shopId == null) {
            return Result.failure("shopId must not be null");
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
    @PostMapping("/sync/{shopId}")
    public Result<Integer> sync(@PathVariable Long shopId) {
        if (shopId == null) {
            return Result.failure("shopId must not be null");
        }
        try {
            int synced = inventorySyncScheduler.syncShopInventory(shopId);
            log.info("manual sync inventory shopId={} count={}", shopId, synced);
            return Result.success(synced);
        } catch (Exception e) {
            log.error("manual sync inventory failed shopId={}", shopId, e);
            return Result.failure("sync failed: " + e.getMessage());
        }
    }
}
