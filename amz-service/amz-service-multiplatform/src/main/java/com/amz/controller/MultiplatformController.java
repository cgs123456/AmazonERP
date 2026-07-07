package com.amz.controller;

import com.amz.model.UnifiedOrder;
import com.amz.result.Result;
import com.amz.service.MultiplatformService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 多平台订单聚合 REST 端点。
 * <p>
 * 支持 Temu / TikTok Shop / Shein 三平台订单同步、查询、发货回传。
 */
@RestController
@RequestMapping("/multiplatform")
public class MultiplatformController {

    @Autowired
    private MultiplatformService multiplatformService;

    /**
     * 同步所有平台订单。
     * POST /multiplatform/sync/all/{shopId}
     */
    @PostMapping("/sync/all/{shopId}")
    public Result<Integer> syncAll(@PathVariable Long shopId) {
        return Result.success(multiplatformService.syncAllPlatforms(shopId));
    }

    /**
     * 同步指定平台订单。
     * POST /multiplatform/sync/{shopId}/{platform}
     */
    @PostMapping("/sync/{shopId}/{platform}")
    public Result<Integer> syncByPlatform(@PathVariable Long shopId,
                                          @PathVariable String platform) {
        return Result.success(multiplatformService.syncByPlatform(shopId, platform));
    }

    /**
     * 查询店铺所有平台订单。
     * GET /multiplatform/order/list/{shopId}
     */
    @GetMapping("/order/list/{shopId}")
    public Result<List<UnifiedOrder>> listOrders(@PathVariable Long shopId) {
        return Result.success(multiplatformService.listOrders(shopId));
    }

    /**
     * 按平台筛选订单。
     * GET /multiplatform/order/list/{shopId}/{platform}
     */
    @GetMapping("/order/list/{shopId}/{platform}")
    public Result<List<UnifiedOrder>> listByPlatform(@PathVariable Long shopId,
                                                     @PathVariable String platform) {
        return Result.success(multiplatformService.listByPlatform(shopId, platform));
    }

    /**
     * 发货回传。
     * POST /multiplatform/order/{orderId}/ship?trackingNo=xxx
     */
    @PostMapping("/order/{orderId}/ship")
    public Result<Boolean> markShipped(@PathVariable Long orderId,
                                       @RequestParam String trackingNo) {
        return Result.success(multiplatformService.markShipped(orderId, trackingNo));
    }
}
