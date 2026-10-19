package com.amz.controller;

import com.amz.annotation.OperLog;
import com.amz.annotation.RequireRole;
import com.amz.annotation.ShopScoped;
import com.amz.result.Result;
import com.amz.service.impl.FbaInboundPlanQueryService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * FBA 入库计划后续步骤接口（读选项 + 回写跟踪号）。
 * <p>
 * 与 {@link FbaInboundPlanController}（创建计划）分开：那条链路的请求体形状被
 * 契约测试钉死，混入后续步骤会让「创建」的校验边界变模糊。
 * <p>
 * <b>为什么没有「确认装箱 / 确认分仓 / 确认运输」端点</b>：这三步都会产生费用或
 * 改变货物去向，必须由运营在看过选项后显式发起。本控制器只暴露<b>读</b>选项与
 * 一个不产生费用的写动作（跟踪号回写）。需要执行确认类操作时，走
 * {@code POST /spapi/operations/{operationId}}——那条路径有完整的限流与审计，
 * 且不会被误当成「一键完成入库」。
 */
@Slf4j
@RestController
@RequestMapping("/procurement/fba/inbound-plan")
public class FbaInboundPlanQueryController {

    @Autowired
    private FbaInboundPlanQueryService queryService;

    /**
     * 计划详情（含 status / 装箱/分仓选项摘要 / 货件列表）。
     */
    @ShopScoped
    @RequireRole({"VIEWER", "OPERATOR", "ADMIN"})
    @GetMapping("/{inboundPlanId}")
    public Result<Map<String, Object>> getPlan(@PathVariable String inboundPlanId,
                                               @RequestParam Long shopId,
                                               @RequestParam String marketplaceId) {
        try {
            return Result.success(queryService.getInboundPlan(shopId, marketplaceId, inboundPlanId));
        } catch (Exception e) {
            log.warn("查询 FBA 入库计划失败 planId={} shopId={}: {}", inboundPlanId, shopId, e.getMessage());
            return Result.failure(e.getMessage());
        }
    }

    /**
     * 装箱选项列表。
     */
    @ShopScoped
    @RequireRole({"VIEWER", "OPERATOR", "ADMIN"})
    @GetMapping("/{inboundPlanId}/packing-options")
    public Result<Map<String, Object>> listPackingOptions(@PathVariable String inboundPlanId,
                                                          @RequestParam Long shopId,
                                                          @RequestParam String marketplaceId) {
        try {
            return Result.success(queryService.listPackingOptions(shopId, marketplaceId, inboundPlanId));
        } catch (Exception e) {
            log.warn("查询装箱选项失败 planId={}: {}", inboundPlanId, e.getMessage());
            return Result.failure(e.getMessage());
        }
    }

    /**
     * 分仓选项列表（含费用/折扣/目的货件）。
     */
    @ShopScoped
    @RequireRole({"VIEWER", "OPERATOR", "ADMIN"})
    @GetMapping("/{inboundPlanId}/placement-options")
    public Result<Map<String, Object>> listPlacementOptions(@PathVariable String inboundPlanId,
                                                            @RequestParam Long shopId,
                                                            @RequestParam String marketplaceId) {
        try {
            return Result.success(queryService.listPlacementOptions(shopId, marketplaceId, inboundPlanId));
        } catch (Exception e) {
            log.warn("查询分仓选项失败 planId={}: {}", inboundPlanId, e.getMessage());
            return Result.failure(e.getMessage());
        }
    }

    /**
     * 单个货件详情。
     */
    @ShopScoped
    @RequireRole({"VIEWER", "OPERATOR", "ADMIN"})
    @GetMapping("/{inboundPlanId}/shipments/{shipmentId}")
    public Result<Map<String, Object>> getShipment(@PathVariable String inboundPlanId,
                                                   @PathVariable String shipmentId,
                                                   @RequestParam Long shopId,
                                                   @RequestParam String marketplaceId) {
        try {
            return Result.success(
                    queryService.getShipment(shopId, marketplaceId, inboundPlanId, shipmentId));
        } catch (Exception e) {
            log.warn("查询货件失败 planId={} shipmentId={}: {}", inboundPlanId, shipmentId, e.getMessage());
            return Result.failure(e.getMessage());
        }
    }

    /**
     * 货件明细列表（SKU / 数量 / prep 指令）。
     */
    @ShopScoped
    @RequireRole({"VIEWER", "OPERATOR", "ADMIN"})
    @GetMapping("/{inboundPlanId}/shipments/{shipmentId}/items")
    public Result<Map<String, Object>> listShipmentItems(@PathVariable String inboundPlanId,
                                                         @PathVariable String shipmentId,
                                                         @RequestParam Long shopId,
                                                         @RequestParam String marketplaceId) {
        try {
            return Result.success(
                    queryService.listShipmentItems(shopId, marketplaceId, inboundPlanId, shipmentId));
        } catch (Exception e) {
            log.warn("查询货件明细失败 planId={} shipmentId={}: {}", inboundPlanId, shipmentId, e.getMessage());
            return Result.failure(e.getMessage());
        }
    }

    /**
     * 把本地逐箱运单号回写给 Amazon。
     * <p>
     * 只支持 SPD（小包裹）逐箱跟踪号，理由见 {@link FbaInboundPlanQueryService#updateTracking}。
     */
    @ShopScoped
    @RequireRole({"OPERATOR", "ADMIN"})
    @OperLog(module = "FBA入库", action = "回写跟踪号", description = "把本地运单号同步给 Amazon")
    @PostMapping("/{inboundPlanId}/shipments/{shipmentId}/tracking")
    public Result<Map<String, Object>> updateTracking(@PathVariable String inboundPlanId,
                                                      @PathVariable String shipmentId,
                                                      @RequestBody TrackingRequest request) {
        if (request == null) {
            return Result.failure("请求体不能为空");
        }
        try {
            return Result.success(queryService.updateTracking(
                    request.getShopId(), request.getMarketplaceId(),
                    inboundPlanId, shipmentId, request.getBoxTracking()));
        } catch (Exception e) {
            log.warn("回写跟踪号失败 planId={} shipmentId={}: {}", inboundPlanId, shipmentId, e.getMessage());
            return Result.failure(e.getMessage());
        }
    }

    /**
     * 异步操作状态查询（配合创建计划返回的 operationId）。
     */
    @ShopScoped
    @RequireRole({"VIEWER", "OPERATOR", "ADMIN"})
    @GetMapping("/operations/{operationId}")
    public Result<Map<String, Object>> getOperationStatus(@PathVariable String operationId,
                                                          @RequestParam Long shopId,
                                                          @RequestParam String marketplaceId) {
        try {
            return Result.success(queryService.getOperationStatus(shopId, marketplaceId, operationId));
        } catch (Exception e) {
            log.warn("查询入库操作状态失败 operationId={}: {}", operationId, e.getMessage());
            return Result.failure(e.getMessage());
        }
    }

    /**
     * 跟踪号回写请求体。
     * <p>
     * {@code boxTracking} 是 {@code boxId → trackingId} 的映射，不是单个主运单号——
     * Amazon 的 SPD 跟踪号是箱级的。用 Map 承载是刻意的：调用方必须逐箱给出，
     * 而不是填一个号就以为覆盖了全部。
     */
    public static class TrackingRequest {
        private Long shopId;
        private String marketplaceId;
        private Map<String, String> boxTracking;

        public Long getShopId() {
            return shopId;
        }

        public void setShopId(Long shopId) {
            this.shopId = shopId;
        }

        public String getMarketplaceId() {
            return marketplaceId;
        }

        public void setMarketplaceId(String marketplaceId) {
            this.marketplaceId = marketplaceId;
        }

        public Map<String, String> getBoxTracking() {
            return boxTracking == null ? new LinkedHashMap<>() : boxTracking;
        }

        public void setBoxTracking(Map<String, String> boxTracking) {
            this.boxTracking = boxTracking;
        }
    }
}
