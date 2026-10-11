package com.amz.controller;
import com.amz.annotation.OperLog;
import com.amz.annotation.RequireRole;
import com.amz.annotation.ShopScoped;

import com.amz.model.InboundOrder;
import com.amz.model.WarehouseInventory;
import com.amz.result.PageRequest;
import com.amz.result.Result;
import com.amz.service.InboundService;
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
    @RequireRole({"OPERATOR", "ADMIN"})
    @OperLog(module = "入库单", action = "新增", description = "创建入库单")
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
                                           @RequestParam(required = false) String status,
                                           @RequestParam(required = false) Integer size,
                                           @RequestParam(required = false) String cursor) {
        return Result.paged(inboundService.listInboundOrders(
                shopId, status, PageRequest.of(size, cursor)));
    }

    /**
     * 入库单状态流转：PENDING → IN_TRANSIT。
     * POST /logistics/inbound/{id}/transit
     */
    @RequireRole({"OPERATOR", "ADMIN"})
    @OperLog(module = "入库单", action = "在途", description = "入库单标记在途")
    @PostMapping("/{id}/transit")
    public Result<InboundOrder> transit(@PathVariable Long id) {
        return Result.success(inboundService.transitInbound(id));
    }

    /**
     * 到货验收 + 库存增加。
     * POST /logistics/inbound/{id}/receive
     */
    @RequireRole({"OPERATOR", "ADMIN"})
    @OperLog(module = "入库单", action = "批量签收", description = "批量入库验收（逐单独立事务，失败不互相影响）")
    @PostMapping("/batch-receive")
    public Result<java.util.List<java.util.Map<String, Object>>> batchReceive(
            @RequestBody java.util.List<BatchReceiveLine> lines) {
        if (lines == null || lines.isEmpty()) {
            return Result.failure("批量到货明细不能为空");
        }
        java.util.List<java.util.Map<String, Object>> report = new java.util.ArrayList<>();
        for (BatchReceiveLine line : lines) {
            java.util.Map<String, Object> row = new java.util.LinkedHashMap<>();
            row.put("inboundId", line.getInboundId());
            try {
                com.amz.model.InboundOrder order = inboundService.receiveInbound(
                        line.getInboundId(), line.getItems());
                row.put("ok", true);
                row.put("status", order.getStatus());
            } catch (Exception e) {
                // 逐单独立：一批到货里一单失败（状态不对/库存行不存在）不应挡住其余单
                row.put("ok", false);
                row.put("error", e.getMessage());
            }
            report.add(row);
        }
        return Result.success(report);
    }

    /** 批量到货的一行：入库单 + 验收明细。 */
    public static class BatchReceiveLine {
        private Long inboundId;
        private java.util.List<com.amz.model.WarehouseInventory> items;

        public Long getInboundId() {
            return inboundId;
        }

        public void setInboundId(Long inboundId) {
            this.inboundId = inboundId;
        }

        public java.util.List<com.amz.model.WarehouseInventory> getItems() {
            return items;
        }

        public void setItems(java.util.List<com.amz.model.WarehouseInventory> items) {
            this.items = items;
        }
    }

    @ShopScoped
    @RequireRole({"OPERATOR", "ADMIN"})
    @OperLog(module = "入库单", action = "签收", description = "入库验收并回补库存")
    @PostMapping("/{id}/receive")
    public Result<InboundOrder> receive(@PathVariable Long id,
                                        @RequestBody List<WarehouseInventory> items) {
        return Result.success(inboundService.receiveInbound(id, items));
    }

    /**
     * 取消入库单。
     * POST /logistics/inbound/{id}/cancel
     */
    @RequireRole({"OPERATOR", "ADMIN"})
    @OperLog(module = "入库单", action = "取消", description = "取消入库单")
    @PostMapping("/{id}/cancel")
    public Result<InboundOrder> cancel(@PathVariable Long id) {
        return Result.success(inboundService.cancelInbound(id));
    }
}
