package com.amz.controller;
import com.amz.annotation.RequireRole;
import com.amz.annotation.ShopScoped;

import com.amz.model.OutboundOrder;
import com.amz.model.WarehouseInventory;
import com.amz.result.PageRequest;
import com.amz.result.Result;
import com.amz.service.OutboundService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 出库单管理 REST 端点。
 * 状态机：PENDING → PICKING → PACKED → SHIPPED / CANCELLED
 */
@RestController
@RequestMapping("/logistics/outbound")
public class OutboundController {

    @Autowired
    private OutboundService outboundService;

    /**
     * 创建出库单。
     * POST /logistics/outbound
     */
    @RequireRole({"OPERATOR", "ADMIN"})
    @PostMapping
    public Result<OutboundOrder> create(@RequestBody OutboundOrder order) {
        return Result.success(outboundService.createOutboundOrder(order));
    }

    /**
     * 查询店铺出库单列表。
     * GET /logistics/outbound/list/{shopId}?status=
     */
    @ShopScoped
    @GetMapping("/list/{shopId}")
    public Result<List<OutboundOrder>> list(@PathVariable Long shopId,
                                            @RequestParam(required = false) String status,
                                            @RequestParam(required = false) Integer size,
                                            @RequestParam(required = false) String cursor) {
        return Result.paged(outboundService.listOutboundOrders(
                shopId, status, PageRequest.of(size, cursor)));
    }

    /**
     * 开始拣货：PENDING → PICKING。
     * POST /logistics/outbound/{id}/pick
     */
    @RequireRole({"OPERATOR", "ADMIN"})
    @PostMapping("/{id}/pick")
    public Result<OutboundOrder> pick(@PathVariable Long id) {
        return Result.success(outboundService.pickOutbound(id));
    }

    /**
     * 打包完成：PICKING → PACKED。
     * POST /logistics/outbound/{id}/pack
     */
    @RequireRole({"OPERATOR", "ADMIN"})
    @PostMapping("/{id}/pack")
    public Result<OutboundOrder> pack(@PathVariable Long id) {
        return Result.success(outboundService.packOutbound(id));
    }

    /**
     * 发货 + 库存扣减：PACKED → SHIPPED。
     * POST /logistics/outbound/{id}/ship
     */
    @RequireRole({"OPERATOR", "ADMIN"})
    @PostMapping("/{id}/ship")
    public Result<OutboundOrder> ship(@PathVariable Long id,
                                      @RequestParam(required = false) String carrier,
                                      @RequestParam(required = false) String trackingNo,
                                      @RequestBody List<WarehouseInventory> items) {
        return Result.success(outboundService.shipOutbound(id, carrier, trackingNo, items));
    }

    /**
     * 取消出库单。
     * POST /logistics/outbound/{id}/cancel
     */
    @RequireRole({"OPERATOR", "ADMIN"})
    @PostMapping("/{id}/cancel")
    public Result<OutboundOrder> cancel(@PathVariable Long id) {
        return Result.success(outboundService.cancelOutbound(id));
    }
}
