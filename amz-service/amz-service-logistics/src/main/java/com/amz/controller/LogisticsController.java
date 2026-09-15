package com.amz.controller;
import com.amz.annotation.RequireRole;
import com.amz.annotation.ShopScoped;

import com.amz.model.Shipment;
import com.amz.model.TrackingEvent;
import com.amz.result.Result;
import com.amz.service.LogisticsService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 物流追踪 REST 端点。
 * <p>
 * 端点设计围绕「一条货件的生命周期」：建单 → 看成到哪了 → 更新轨迹 → 结案。
 * 看板聚合类接口见 {@link LogisticsDashboardController}，
 * 外部批量导入见 {@link LogisticsImportController}。
 */
@RestController
@RequestMapping("/logistics")
public class LogisticsController {

    @Autowired
    private LogisticsService logisticsService;

    /**
     * 创建头程物流单 / FBA 货件。
     * POST /logistics/shipment
     */
    @PostMapping("/shipment")
    public Result<Shipment> createShipment(@RequestBody Shipment shipment) {
        return Result.success(logisticsService.createShipment(shipment));
    }

    /**
     * 查询店铺货件列表。
     * GET /logistics/shipment/list/{shopId}?status=
     */
    @ShopScoped
    @GetMapping("/shipment/list/{shopId}")
    public Result<List<Shipment>> listShipments(
            @PathVariable Long shopId,
            @RequestParam(required = false) String status) {
        return Result.success(logisticsService.listShipments(shopId, status));
    }

    /**
     * 同步货件状态（拉取承运商轨迹）。
     * POST /logistics/shipment/{shipmentId}/sync
     * <p>
     * 归属校验在服务层完成（依据 UserContext 的授权店铺），
     * 此处不加 {@code @ShopScoped}：该注解只识别方法签名中的 shopId 参数，
     * 本端点没有该参数，加上会造成「已受保护」的错觉。
     */
    @RequireRole({"OPERATOR", "ADMIN"})
    @PostMapping("/shipment/{shipmentId}/sync")
    public Result<Shipment> syncStatus(@PathVariable Long shipmentId) {
        return Result.success(logisticsService.syncShipmentStatus(shipmentId));
    }

    /**
     * 查询货件完整轨迹（轨迹可视化）。
     * GET /logistics/shipment/{shipmentId}/tracking
     * <p>
     * 归属校验同样在服务层完成——轨迹表不含店铺字段，
     * 数据一旦取出来租户边界就已被越过，必须在校验之后才查。
     */
    @GetMapping("/shipment/{shipmentId}/tracking")
    public Result<List<TrackingEvent>> getTracking(@PathVariable Long shipmentId) {
        return Result.success(logisticsService.getTrackingTimeline(shipmentId));
    }

    /**
     * 手工关闭货件。
     * POST /logistics/shipment/{shipmentId}/close?shopId=1
     * <p>
     * 用于补齐状态机终点：CLOSED 是终态但没有自动流程会产出它，
     * 缺少该入口则「确认不再送货」的货件永远停留在在途，污染看板计数并被持续轮询。
     * 幂等：重复关闭返回当前状态而不报错。
     */
    @RequireRole({"OPERATOR", "ADMIN"})
    @ShopScoped
    @PostMapping("/shipment/{shipmentId}/close")
    public Result<Shipment> closeShipment(@PathVariable Long shipmentId,
                                          @RequestParam Long shopId) {
        return Result.success(logisticsService.closeShipment(shipmentId, shopId));
    }
}
