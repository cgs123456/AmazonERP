package com.amz.controller;
import com.amz.annotation.ShopScoped;

import com.amz.model.InboundOrder;
import com.amz.model.WarehouseInventory;
import com.amz.result.Result;
import com.amz.service.InboundService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 入库单管理 REST 端点。
 * 状态机：PENDING → IN_TRANSIT → RECEIVED / PARTIAL / CANCELLED
 */
@RestController
@RequestMapping("/logistics/inbound")
public class InboundController {

    @Autowired
    private InboundService inboundService;

    /**
     * 创建入库单。
     * POST /logistics/inbound
     */
    @PostMapping
    public Result<InboundOrder> create(@RequestBody InboundOrder order) {
        return Result.success(inboundService.createInboundOrder(order));
    }

    /**
     * 查询店铺入库单列表。
     * GET /logistics/inbound/list/{shopId}?status=
     */
    @ShopScoped
    @GetMapping("/list/{shopId}")
    public Result<List<InboundOrder>> list(@PathVariable Long shopId,
                                           @RequestParam(required = false) String status) {
        return Result.success(inboundService.listInboundOrders(shopId, status));
    }

    /**
     * 入库单状态流转：PENDING → IN_TRANSIT。
     * POST /logistics/inbound/{id}/transit
     */
    @PostMapping("/{id}/transit")
    public Result<InboundOrder> transit(@PathVariable Long id) {
        return Result.success(inboundService.transitInbound(id));
    }

    /**
     * 到货验收 + 库存增加。
     * POST /logistics/inbound/{id}/receive
     */
    @PostMapping("/{id}/receive")
    public Result<InboundOrder> receive(@PathVariable Long id,
                                        @RequestBody List<WarehouseInventory> items) {
        return Result.success(inboundService.receiveInbound(id, items));
    }

    /**
     * 取消入库单。
     * POST /logistics/inbound/{id}/cancel
     */
    @PostMapping("/{id}/cancel")
    public Result<InboundOrder> cancel(@PathVariable Long id) {
        return Result.success(inboundService.cancelInbound(id));
    }
}
