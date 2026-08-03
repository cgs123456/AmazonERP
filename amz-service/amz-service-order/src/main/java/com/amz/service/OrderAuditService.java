package com.amz.service;

import com.amz.model.*;
import com.amz.model.pojo.Order;

import java.util.List;
import java.util.Map;

/**
 * 订单智能审单服务。
 */
public interface OrderAuditService {

    // ==================== 审单规则 ====================
    OrderAuditRule createRule(OrderAuditRule rule);
    OrderAuditRule updateRule(OrderAuditRule rule);
    List<OrderAuditRule> listRules(Long shopId, Boolean enabled);
    void toggleRule(Long id, boolean enabled);
    void deleteRule(Long id);

    // ==================== 审单执行 ====================
    Map<String, Object> auditOrder(Long shopId, Order order);
    List<Map<String, Object>> batchAudit(Long shopId, List<Order> orders);

    // ==================== 发货路由 ====================
    ShipmentRouting routeOrder(Long shopId, String orderId, String sku, String asin, Integer quantity, String country);

    // ==================== 拆分日志 ====================
    List<OrderSplitLog> listSplitLogs(Long shopId, String originalOrderId);
}
