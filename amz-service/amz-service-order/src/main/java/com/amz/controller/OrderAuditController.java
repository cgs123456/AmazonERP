package com.amz.controller;

import com.amz.annotation.ShopScoped;
import com.amz.model.*;
import com.amz.model.pojo.Order;
import com.amz.result.Result;
import com.amz.service.OrderAuditService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 订单智能审单 REST 端点。
 */
@RestController
@RequestMapping("/order/audit")
public class OrderAuditController {

    @Autowired
    private OrderAuditService orderAuditService;

    // ==================== 审核规则 ====================

    /** 创建审单规则 */
    @ShopScoped
    @PostMapping("/rule")
    public Result<OrderAuditRule> createRule(@RequestBody OrderAuditRule rule) {
        return Result.success(orderAuditService.createRule(rule));
    }

    /** 更新规则 */
    @ShopScoped
    @PutMapping("/rule/{id}")
    public Result<OrderAuditRule> updateRule(@PathVariable Long id, @RequestBody OrderAuditRule rule) {
        rule.setId(id);
        return Result.success(orderAuditService.updateRule(rule));
    }

    /** 查询规则列表 */
    @ShopScoped
    @GetMapping("/rule/list/{shopId}")
    public Result<List<OrderAuditRule>> listRules(@PathVariable Long shopId,
                                                   @RequestParam(required = false) Boolean enabled) {
        return Result.success(orderAuditService.listRules(shopId, enabled));
    }

    /** 启用/禁用规则 */
    @ShopScoped
    @PostMapping("/rule/{id}/toggle")
    public Result<Boolean> toggleRule(@PathVariable Long id, @RequestParam boolean enabled) {
        orderAuditService.toggleRule(id, enabled);
        return Result.success(true);
    }

    /** 删除规则 */
    @ShopScoped
    @DeleteMapping("/rule/{id}")
    public Result<Boolean> deleteRule(@PathVariable Long id) {
        orderAuditService.deleteRule(id);
        return Result.success(true);
    }

    // ==================== 审单执行 ====================

    /** 对单个订单执行审单 */
    @ShopScoped
    @PostMapping("/order/{shopId}")
    public Result<Map<String, Object>> auditOrder(@PathVariable Long shopId,
                                                   @RequestBody Order order) {
        return Result.success(orderAuditService.auditOrder(shopId, order));
    }

    /** 批量审单 */
    @ShopScoped
    @PostMapping("/batch/{shopId}")
    public Result<List<Map<String, Object>>> batchAudit(@PathVariable Long shopId,
                                                         @RequestBody List<Order> orders) {
        return Result.success(orderAuditService.batchAudit(shopId, orders));
    }

    // ==================== 发货路由 ====================

    /** 订单发货路由建议 */
    @ShopScoped
    @GetMapping("/route/{shopId}")
    public Result<ShipmentRouting> routeOrder(@PathVariable Long shopId,
                                              @RequestParam String orderId,
                                              @RequestParam String sku,
                                              @RequestParam(required = false) String asin,
                                              @RequestParam(defaultValue = "1") Integer quantity,
                                              @RequestParam(defaultValue = "US") String country) {
        return Result.success(orderAuditService.routeOrder(shopId, orderId, sku, asin, quantity, country));
    }

    // ==================== 拆分日志 ====================

    /** 查询拆分日志 */
    @ShopScoped
    @GetMapping("/split-log/list/{shopId}")
    public Result<List<OrderSplitLog>> listSplitLogs(@PathVariable Long shopId,
                                                      @RequestParam(required = false) String originalOrderId) {
        return Result.success(orderAuditService.listSplitLogs(shopId, originalOrderId));
    }
}
