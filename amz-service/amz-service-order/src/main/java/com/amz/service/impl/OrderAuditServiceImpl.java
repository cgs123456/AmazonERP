package com.amz.service.impl;

import com.amz.mapper.*;
import com.amz.model.*;
import com.amz.model.pojo.Order;
import com.amz.service.OrderAuditService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 订单智能审单服务实现。
 * <p>
 * 规则引擎驱动订单审核：地址校验/重复检测/风险标记/合并/拆分/路由。
 */
@Slf4j
@Service
public class OrderAuditServiceImpl implements OrderAuditService {

    @Autowired
    private OrderAuditRuleMapper orderAuditRuleMapper;
    @Autowired
    private OrderSplitLogMapper orderSplitLogMapper;
    @Autowired
    private ShipmentRoutingMapper shipmentRoutingMapper;
    @Autowired
    private OrderMapper orderMapper;

    // ==================== 审单规则 CRUD ====================

    @Override
    public OrderAuditRule createRule(OrderAuditRule rule) {
        if (rule.getPriority() == null) rule.setPriority(0);
        if (rule.getEnabled() == null) rule.setEnabled(true);
        orderAuditRuleMapper.insert(rule);
        return rule;
    }

    @Override
    public OrderAuditRule updateRule(OrderAuditRule rule) {
        orderAuditRuleMapper.updateById(rule);
        return rule;
    }

    @Override
    public List<OrderAuditRule> listRules(Long shopId, Boolean enabled) {
        LambdaQueryWrapper<OrderAuditRule> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(OrderAuditRule::getShopId, shopId);
        if (enabled != null) wrapper.eq(OrderAuditRule::getEnabled, enabled);
        wrapper.orderByAsc(OrderAuditRule::getPriority);
        return orderAuditRuleMapper.selectList(wrapper);
    }

    @Override
    public void toggleRule(Long id, boolean enabled) {
        OrderAuditRule rule = orderAuditRuleMapper.selectById(id);
        if (rule != null) {
            rule.setEnabled(enabled);
            orderAuditRuleMapper.updateById(rule);
        }
    }

    @Override
    public void deleteRule(Long id) {
        orderAuditRuleMapper.deleteById(id);
    }

    // ==================== 审单执行 ====================

    @Override
    public Map<String, Object> auditOrder(Long shopId, Order order) {
        List<OrderAuditRule> rules = listRules(shopId, true);
        List<Map<String, Object>> alerts = new ArrayList<>();
        List<String> actions = new ArrayList<>();
        boolean blocked = false;

        for (OrderAuditRule rule : rules) {
            try {
                boolean matched = evaluateCondition(rule, order);
                if (!matched) continue;

                Map<String, Object> alert = new LinkedHashMap<>();
                alert.put("ruleId", rule.getId());
                alert.put("ruleName", rule.getRuleName());
                alert.put("ruleType", rule.getRuleType());
                alert.put("action", rule.getAction());
                alert.put("description", rule.getDescription());
                alerts.add(alert);

                switch (rule.getAction()) {
                    case "BLOCK":
                        blocked = true;
                        actions.add("BLOCK");
                        break;
                    case "FLAG":
                        actions.add("FLAG");
                        break;
                    case "ALERT":
                        actions.add("ALERT");
                        break;
                    case "MERGE":
                        actions.add("MERGE");
                        break;
                    case "SPLIT":
                        actions.add("SPLIT");
                        break;
                    default:
                        log.info("审单规则 {} 触发未知动作 {}", rule.getRuleName(), rule.getAction());
                }
            } catch (Exception e) {
                log.warn("审单规则 {} 执行异常：{}", rule.getRuleName(), e.getMessage());
            }
        }

        String verdict = blocked ? "BLOCKED" : (alerts.isEmpty() ? "PASS" : "REVIEW");

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("orderId", order.getAmazonOrderId() != null ? order.getAmazonOrderId() : order.getId());
        result.put("shopId", shopId);
        result.put("verdict", verdict);
        result.put("alerts", alerts);
        result.put("actions", actions);
        result.put("alertCount", alerts.size());
        result.put("auditTime", LocalDateTime.now().toString());
        return result;
    }

    @Override
    public List<Map<String, Object>> batchAudit(Long shopId, List<Order> orders) {
        return orders.stream().map(o -> auditOrder(shopId, o)).collect(Collectors.toList());
    }

    /**
     * 评估单条规则是否匹配订单。
     */
    private boolean evaluateCondition(OrderAuditRule rule, Order order) {
        String fieldValue = extractFieldValue(rule.getConditionField(), order);
        if (fieldValue == null) return false;

        switch (rule.getConditionOp()) {
            case "EQ":
                return fieldValue.equalsIgnoreCase(rule.getConditionValue());
            case "NEQ":
                return !fieldValue.equalsIgnoreCase(rule.getConditionValue());
            case "CONTAINS":
                return fieldValue.toUpperCase().contains(rule.getConditionValue().toUpperCase());
            case "GT":
                try { return new BigDecimal(fieldValue).compareTo(new BigDecimal(rule.getConditionValue())) > 0; }
                catch (NumberFormatException e) { return false; }
            case "LT":
                try { return new BigDecimal(fieldValue).compareTo(new BigDecimal(rule.getConditionValue())) < 0; }
                catch (NumberFormatException e) { return false; }
            case "GTE":
                try { return new BigDecimal(fieldValue).compareTo(new BigDecimal(rule.getConditionValue())) >= 0; }
                catch (NumberFormatException e) { return false; }
            case "LTE":
                try { return new BigDecimal(fieldValue).compareTo(new BigDecimal(rule.getConditionValue())) <= 0; }
                catch (NumberFormatException e) { return false; }
            case "REGEX":
                return safeRegexMatch(rule.getConditionValue(), fieldValue);
            default:
                return false;
        }
    }

    /**
     * 正则匹配专用线程池：与 ForkJoinPool.commonPool 隔离。
     * 旧实现跑在 commonPool 上，超时仅放弃等待并不取消任务，
     * ReDoS 回溯线程会持续占用 JVM 公共池，殃及并行流等其他组件。
     */
    private static final java.util.concurrent.ExecutorService REGEX_EXECUTOR =
            java.util.concurrent.Executors.newFixedThreadPool(2, r -> {
                Thread t = new Thread(r, "order-audit-regex");
                t.setDaemon(true);
                return t;
            });

    /**
     * 安全执行正则匹配，防御店铺管理员可控正则带来的灾难性回溯（ReDoS）拒绝服务：
     *  1. 限制正则长度，拒绝超长 / 畸形输入；
     *  2. 在专用小线程池中执行匹配并加 500ms 墙钟超时；
     *  3. 超时/异常时 cancel(true) 中断匹配线程（Thread.interrupt 可打断部分
     *     灾难性回溯循环），避免失控任务滞留线程池。
     */
    private boolean safeRegexMatch(String pattern, String fieldValue) {
        if (pattern == null || pattern.length() > 256) {
            log.warn("[safeRegexMatch] 正则过长或为空，跳过匹配 len={}", pattern == null ? -1 : pattern.length());
            return false;
        }
        java.util.concurrent.Future<Boolean> future = null;
        try {
            Pattern compiled = Pattern.compile(pattern);
            String input = fieldValue != null ? fieldValue : "";
            future = REGEX_EXECUTOR.submit(() -> compiled.matcher(input).find());
            return future.get(500, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            return false;
        } finally {
            if (future != null) {
                // 无论成功失败都尝试中断：超时的回溯任务不应继续占用线程
                future.cancel(true);
            }
        }
    }

    /**
     * 从订单对象提取条件字段值。
     */
    private String extractFieldValue(String field, Order order) {
        if (field == null) return null;
        switch (field) {
            case "shipping_address":
                // Order 模型当前无地址字段，返回空字符串；
                // 地址信息可从 OrderAttribute 或 SP-API getOrder 扩展后读入
                return "";
            case "fulfillment_channel":
                return order.getFulfillmentChannel() != null ? order.getFulfillmentChannel() : "";
            case "order_status":
                return order.getOrderStatus() != null ? order.getOrderStatus() : "";
            case "final_price":
                return order.getFinalPrice() != null ? order.getFinalPrice().toString() : "0";
            case "marketplace_id":
                return order.getMarketplaceId() != null ? order.getMarketplaceId() : "";
            case "buyer_name":
                return order.getBuyerName() != null ? order.getBuyerName() : "";
            case "amazon_order_id":
                return order.getAmazonOrderId() != null ? order.getAmazonOrderId() : "";
            default:
                return "";
        }
    }

    // ==================== 发货路由 ====================

    @Override
    public ShipmentRouting routeOrder(Long shopId, String orderId, String sku, String asin,
                                       Integer quantity, String country) {
        // 简化的默认路由：FBA优先 → 海外仓 → 本地仓
        ShipmentRouting routing = new ShipmentRouting();
        routing.setShopId(shopId);
        routing.setOrderId(orderId);
        routing.setSku(sku);
        routing.setAsin(asin);
        routing.setQuantity(quantity);

        // 默认策略：FBA 覆盖国家用 FBA，否则海外仓
        boolean isFbaCountry = country != null && (country.equals("US") || country.equals("CA")
                || country.equals("MX") || country.equals("GB") || country.equals("DE")
                || country.equals("FR") || country.equals("IT") || country.equals("ES")
                || country.equals("JP") || country.equals("AU"));
        if (isFbaCountry) {
            routing.setWarehouseType("FBA");
            routing.setWarehouseName(country + "-FBA-Warehouse");
            routing.setSelectedReason("FBA主配送国家");
        } else {
            routing.setWarehouseType("OVERSEAS");
            routing.setWarehouseName(country + "-Overseas-Warehouse");
            routing.setSelectedReason("非FBA覆盖国家，走海外仓自发货");
        }
        routing.setRouteTime(LocalDateTime.now());
        shipmentRoutingMapper.insert(routing);
        return routing;
    }

    // ==================== 拆分日志 ====================

    @Override
    public List<OrderSplitLog> listSplitLogs(Long shopId, String originalOrderId) {
        LambdaQueryWrapper<OrderSplitLog> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(OrderSplitLog::getShopId, shopId);
        if (originalOrderId != null && !originalOrderId.isBlank())
            wrapper.eq(OrderSplitLog::getOriginalOrderId, originalOrderId);
        wrapper.orderByDesc(OrderSplitLog::getSplitTime);
        return orderSplitLogMapper.selectList(wrapper);
    }
}
