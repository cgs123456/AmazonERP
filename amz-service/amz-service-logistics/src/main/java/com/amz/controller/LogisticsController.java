package com.amz.controller;

import com.amz.model.Shipment;
import com.amz.model.TrackingEvent;
import com.amz.result.Result;
import com.amz.service.LogisticsService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 物流追踪 REST 端点。
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
    @GetMapping("/shipment/list/{shopId}")
    public Result<List<Shipment>> listShipments(
            @PathVariable Long shopId,
            @RequestParam(required = false) String status) {
        return Result.success(logisticsService.listShipments(shopId, status));
    }

    /**
     * 同步货件状态（拉取承运商轨迹）。
     * POST /logistics/shipment/{shipmentId}/sync
     */
    @PostMapping("/shipment/{shipmentId}/sync")
    public Result<Shipment> syncStatus(@PathVariable Long shipmentId) {
        return Result.success(logisticsService.syncShipmentStatus(shipmentId));
    }

    /**
     * 查询货件完整轨迹（轨迹可视化）。
     * GET /logistics/shipment/{shipmentId}/tracking
     */
    @GetMapping("/shipment/{shipmentId}/tracking")
    public Result<List<TrackingEvent>> getTracking(@PathVariable Long shipmentId) {
        return Result.success(logisticsService.getTrackingTimeline(shipmentId));
    }
}
