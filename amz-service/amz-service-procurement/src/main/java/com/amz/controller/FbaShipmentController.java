package com.amz.controller;

import com.amz.annotation.OperLog;
import com.amz.annotation.RequireRole;
import com.amz.annotation.ShopScoped;
import com.amz.dto.BatchCostSummary;
import com.amz.dto.ReceiptShortage;
import com.amz.model.FbaShipment;
import com.amz.model.FbaShipmentItem;
import com.amz.model.InventoryBatch;
import com.amz.result.PageRequest;
import com.amz.result.Result;
import com.amz.service.FbaShipmentService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * FBA 货件 + 库存批次端点（从 ProcurementController 拆出，§S32）。
 * <p>
 * <b>为什么是这一刀</b>：FBA 签收回补批次、FIFO 出库/退货、批次成本聚合与货件明细
 * 是同一个子域（"货进了 FBA 仓、怎么算成本、怎么扣批次"）——它们在原文件里共用
 * 同一个 {@code fbaShipmentService} 依赖，与原 Controller 的供应商/计划/订单段互不引用。
 * 拆开既让各自职责单一，又正好把 ProcurementController 压到 500 行阈值以下。
 * <p>
 * <b>路由刻意保持完全不变</b>：类级 {@code @RequestMapping("/procurement")} +
 * 方法级 {@code /fba/shipment*}、{@code /batch*} 与拆分前逐字相同，前端与跨服务调用
 * （batch/cost-summary 供 finance 侧 Feign 消费）零改动。两个 Controller 共用
 * {@code /procurement} 前缀不冲突——Spring 只禁止方法级路径重复，这里方法路径不重叠。
 * <p>
 * 守卫注解（{@code @ShopScoped}/{@code @RequireRole}/{@code @OperLog}）原样带过来，
 * 一个都没删——拆文件不是放松门禁的时机。
 */
@RestController
@RequestMapping("/procurement")
public class FbaShipmentController {

    @Autowired
    private FbaShipmentService fbaShipmentService;

    // ==================== FBA 货件管理 ====================

    /** 创建 FBA 货件 */
    @ShopScoped
    @RequireRole({"OPERATOR", "ADMIN"})
    @OperLog(module = "FBA货件", action = "新增", description = "创建 FBA 发货计划")
    @PostMapping("/fba/shipment")
    public Result<FbaShipment> createShipment(@RequestBody FbaShipment shipment) {
        return Result.success(fbaShipmentService.createShipment(shipment));
    }

    /** 更新 FBA 货件 */
    @ShopScoped
    @RequireRole({"OPERATOR", "ADMIN"})
    @OperLog(module = "采购供应链", action = "修改", description = "更新 FBA 货件")
    @PutMapping("/fba/shipment/{id}")
    public Result<FbaShipment> updateShipment(@PathVariable Long id, @RequestBody FbaShipment shipment) {
        shipment.setId(id);
        return Result.success(fbaShipmentService.updateShipment(shipment));
    }

    /** 添加货件明细 */
    @ShopScoped
    @RequireRole({"OPERATOR", "ADMIN"})
    @OperLog(module = "采购供应链", action = "新增", description = "添加货件明细")
    @PostMapping("/fba/shipment/{shipmentId}/item")
    public Result<FbaShipmentItem> addShipmentItem(@PathVariable Long shipmentId,
                                                    @RequestBody FbaShipmentItem item) {
        item.setFbaShipmentId(shipmentId);
        return Result.success(fbaShipmentService.addShipmentItem(item));
    }

    /** 查询货件明细（id DESC keyset 游标分页） */
    @ShopScoped
    @GetMapping("/fba/shipment/{shipmentId}/items")
    public Result<List<FbaShipmentItem>> listShipmentItems(
            @PathVariable Long shipmentId,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String cursor) {
        return Result.paged(fbaShipmentService.listShipmentItems(
                shipmentId, PageRequest.of(size, cursor)));
    }

    /** 查询货件列表 */
    @ShopScoped
    @GetMapping("/fba/shipment/list/{shopId}")
    public Result<List<FbaShipment>> listShipments(@PathVariable Long shopId,
                                                    @RequestParam(value = "status", required = false) String status,
                                                    @RequestParam(required = false) Integer size,
                                                    @RequestParam(required = false) String cursor) {
        return Result.paged(fbaShipmentService.listShipments(
                shopId, status, PageRequest.of(size, cursor)));
    }

    /** 获取货件详情 */
    @ShopScoped
    @GetMapping("/fba/shipment/{id}")
    public Result<FbaShipment> getShipment(@PathVariable Long id) {
        return Result.success(fbaShipmentService.getShipment(id));
    }

    /** 更新货件状态 */
    @ShopScoped
    @RequireRole({"OPERATOR", "ADMIN"})
    @OperLog(module = "采购供应链", action = "状态变更", description = "推进货件状态")
    @PostMapping("/fba/shipment/{id}/status")
    public Result<FbaShipment> updateShipmentStatus(@PathVariable Long id, @RequestParam String status) {
        return Result.success(fbaShipmentService.updateShipmentStatus(id, status));
    }

    /** 确认发货 */
    @ShopScoped
    @RequireRole({"OPERATOR", "ADMIN"})
    @OperLog(module = "FBA货件", action = "发货", description = "标记货件已发运")
    @PostMapping("/fba/shipment/{id}/ship")
    public Result<FbaShipment> confirmShipment(@PathVariable Long id,
                                                @RequestParam String carrier,
                                                @RequestParam String trackingNo) {
        return Result.success(fbaShipmentService.confirmShipment(id, carrier, trackingNo));
    }

    /** 头程费用分摊 */
    @ShopScoped
    @RequireRole({"OPERATOR", "ADMIN"})
    @OperLog(module = "采购供应链", action = "分摊", description = "货件成本分摊")
    @PostMapping("/fba/shipment/{shipmentId}/allocate")
    public Result<Map<String, Object>> allocateCosts(@PathVariable Long shipmentId) {
        return Result.success(fbaShipmentService.allocateCosts(shipmentId));
    }

    /** FBA 签收处理 */
    @ShopScoped
    @RequireRole({"OPERATOR", "ADMIN"})
    @OperLog(module = "FBA货件", action = "签收", description = "货件签收并回补批次库存")
    @PostMapping("/fba/shipment/{shipmentId}/receive")
    public Result<Map<String, Object>> processReceipt(@PathVariable Long shipmentId,
                                                       @RequestBody List<Map<String, Object>> receivedItems) {
        return Result.success(fbaShipmentService.processReceipt(shipmentId, receivedItems));
    }

    /**
     * 入库短收待登记清单（GET /procurement/fba/shipment/receipt-shortages/{shopId}?size=&cursor=）。
     * <p>
     * 给财务侧「登记入库短收」表单当数据来源：只交事实（应发/实发/短收 + 我方成本口径），
     * 金额与币种由人在登记时确认，因为分摊金额没有记币种。
     */
    @ShopScoped
    @GetMapping("/fba/shipment/receipt-shortages/{shopId}")
    public Result<List<ReceiptShortage>> listReceiptShortages(@PathVariable Long shopId,
                                                              @RequestParam(required = false) Integer size,
                                                              @RequestParam(required = false) String cursor) {
        return Result.paged(fbaShipmentService.listReceiptShortages(shopId, PageRequest.of(size, cursor)));
    }

    // ==================== 批次管理 ====================

    /** 查询 SKU 库存批次（按 inbound_date、id 升序，FIFO 游标分页） */
    @ShopScoped
    @GetMapping("/batch/list/{shopId}")
    public Result<List<InventoryBatch>> listBatches(@PathVariable Long shopId,
                                                     @RequestParam String sku,
                                                     @RequestParam(value = "size", required = false) Integer size,
                                                     @RequestParam(value = "cursor", required = false) String cursor) {
        return Result.paged(fbaShipmentService.listBatchesBySku(
                shopId, sku, PageRequest.of(size, cursor)));
    }

    /** 查询 SKU 的 ACTIVE 批次成本聚合值（供财务跨服务消费） */
    @ShopScoped
    @GetMapping("/batch/cost-summary/{shopId}")
    public Result<BatchCostSummary> batchCostSummary(@PathVariable Long shopId,
                                                      @RequestParam String sku) {
        return Result.success(fbaShipmentService.getBatchCostSummary(shopId, sku));
    }

    /** FIFO 出库 */
    @ShopScoped
    @RequireRole({"OPERATOR", "ADMIN"})
    @OperLog(module = "库存批次", action = "出库", description = "FIFO 批次出库扣减")
    @PostMapping("/batch/fifo-outbound")
    public Result<List<Map<String, Object>>> fifoOutbound(@RequestParam Long shopId,
                                                          @RequestParam String sku,
                                                          @RequestParam Integer quantity) {
        return Result.success(fbaShipmentService.fifoOutbound(shopId, sku, quantity));
    }

    /** FIFO 回补（退货/冲回，与出库对称） */
    @ShopScoped
    @RequireRole({"OPERATOR", "ADMIN"})
    @OperLog(module = "库存批次", action = "回补", description = "FIFO 批次回补（退货冲回）")
    @PostMapping("/batch/fifo-return")
    public Result<List<Map<String, Object>>> fifoReturn(@RequestParam Long shopId,
                                                        @RequestParam String sku,
                                                        @RequestParam Integer quantity,
                                                        @RequestParam(required = false) String originalBatchNo) {
        return Result.success(fbaShipmentService.fifoReturn(shopId, sku, quantity, originalBatchNo));
    }
}
