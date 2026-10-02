package com.amz.service.impl;

import com.amz.context.UserContext;
import com.amz.exception.AttrIsNullException;
import com.amz.exception.CodeErrorException;
import com.amz.mapper.OrderAuditRuleMapper;
import com.amz.mapper.OrderMapper;
import com.amz.mapper.OrderSplitLogMapper;
import com.amz.mapper.ShipmentRoutingMapper;
import com.amz.model.OrderAuditRule;
import com.amz.model.OrderSplitLog;
import com.amz.model.ShipmentRouting;
import com.amz.model.pojo.Order;
import com.amz.service.OrderAuditService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
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
        // @ShopScoped 对「shopId 只在 @RequestBody 里」的端点是空转的：切面只认名为 shopId 的
        // PathVariable/RequestParam。所以归属必须在这里兜住，否则任何登录用户都能给别人建规则。
        requireShopAccess(rule.getShopId(), "新建审单规则");
        if (rule.getPriority() == null) rule.setPriority(0);
        if (rule.getEnabled() == null) rule.setEnabled(true);
        orderAuditRuleMapper.insert(rule);
        return rule;
    }

    @Override
    public OrderAuditRule updateRule(OrderAuditRule rule) {
        OrderAuditRule existing = requireOwnedRule(rule.getId());
        if (rule.getShopId() != null && !rule.getShopId().equals(existing.getShopId())) {
            throw new CodeErrorException("不允许跨店铺移动审单规则：id=" + existing.getId());
        }
        rule.setShopId(existing.getShopId());
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
        OrderAuditRule rule = requireOwnedRule(id);
        rule.setEnabled(enabled);
        orderAuditRuleMapper.updateById(rule);
    }

    @Override
    public void deleteRule(Long id) {
        requireOwnedRule(id);
        orderAuditRuleMapper.deleteById(id);
    }

    /**
     * 校验调用方对该店铺的写权限。
     */
    private static void requireShopAccess(Long shopId, String what) {
        if (shopId == null) {
            throw new AttrIsNullException(what + "：店铺ID不能为空");
        }
        if (!UserContext.isShopAllowedStrict(shopId)) {
            log.warn("审单规则越权写入拦截：what={}, userId={}, role={}, shopId={}, authorizedShops={}",
                    what, UserContext.getUserId(), UserContext.getRole(), shopId, UserContext.getShops());
            throw new CodeErrorException("店铺不存在或无权写入：" + shopId);
        }
    }

    /**
     * 按 id 定位的规则必须先查出行、再判归属：这类端点没有 shopId 参数，
     * {@code @ShopScoped} 完全空转。「行不存在」与「不是你的店铺」返回同一个错误，
     * 避免把「哪家店有这条规则」泄露给探测者。旧实现里 toggleRule 对不存在的行静默 no-op
     * 并让 Controller 返回 true，等于把「什么都没做」显示成「已启用/已停用」。
     */
    private OrderAuditRule requireOwnedRule(Long id) {
        if (id == null) {
            throw new AttrIsNullException("规则ID不能为空");
        }
        OrderAuditRule rule = orderAuditRuleMapper.selectById(id);
        if (rule == null || !UserContext.isShopAllowedStrict(rule.getShopId())) {
            log.warn("审单规则写操作被拒：id={}, 命中行={}, 行归属店铺={}, userId={}, role={}, authorizedShops={}",
                    id, rule != null, rule == null ? null : rule.getShopId(),
                    UserContext.getUserId(), UserContext.getRole(), UserContext.getShops());
            throw new CodeErrorException("审单规则不存在或无权访问");
        }
        return rule;
    }

    // ==================== 审单执行 ====================

    @Override
    public Map<String, Object> auditOrder(Long shopId, Order order) {
        List<OrderAuditRule> rules = listRules(shopId, true);
        List<Map<String, Object>> alerts = new ArrayList<>();
        List<String> actions = new ArrayList<>();
        List<Map<String, Object>> unevaluated = new ArrayList<>();
        // 只给建议、不改变数据的动作（MERGE/SPLIT）：系统里没有拆合单实现，
        // amz_order_split_log 也全仓零插入点，把它们和 BLOCK/FLAG 混在 actions 里
        // 会让运营以为订单已经被合并/拆分过。
        List<String> advisoryActions = new ArrayList<>();
        boolean blocked = false;

        for (OrderAuditRule rule : rules) {
            Outcome outcome;
            try {
                outcome = evaluateCondition(rule, order);
            } catch (Exception e) {
                log.warn("审单规则 {} 执行异常，按未判定处理：{}", rule.getRuleName(), e.toString(), e);
                unevaluated.add(unevaluatedEntry(rule, "执行异常：" + e.getClass().getSimpleName()));
                continue;
            }
            if (!outcome.evaluated()) {
                log.warn("审单规则 {}（action={}）无法判定：{}",
                        rule.getRuleName(), rule.getAction(), outcome.reason());
                unevaluated.add(unevaluatedEntry(rule, outcome.reason()));
                continue;
            }
            if (!Boolean.TRUE.equals(outcome.matched())) {
                continue;
            }

            Map<String, Object> alert = new LinkedHashMap<>();
            alert.put("ruleId", rule.getId());
            alert.put("ruleName", rule.getRuleName());
            alert.put("ruleType", rule.getRuleType());
            alert.put("action", rule.getAction());
            alert.put("description", rule.getDescription());
            alerts.add(alert);

            switch (rule.getAction() == null ? "" : rule.getAction()) {
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
                    advisoryActions.add("MERGE");
                    break;
                case "SPLIT":
                    actions.add("SPLIT");
                    advisoryActions.add("SPLIT");
                    break;
                default:
                    // 动作名写错的规则等于没生效：这里必须留下 WARN 级痕迹，
                    // 不能再按 info 记一笔就当作已处理。
                    log.warn("审单规则 {} 配置了未知动作 {}，该规则不会改变 verdict",
                            rule.getRuleName(), rule.getAction());
                    unevaluated.add(unevaluatedEntry(rule, "未知动作 " + rule.getAction()));
            }
        }

        // fail-closed：规则"没能判定"不等于"没有风险"。旧实现把无法判定（正则超时 / ReDoS /
        // 畸形正则 / 未知操作符 / 字段取不到值 / 执行异常）一律折成 false，
        // 于是 BLOCK 类规则在一次线程池排队之后被整体绕过，订单直接 PASS。
        String verdict;
        if (blocked) {
            verdict = "BLOCKED";
        } else if (!alerts.isEmpty() || !unevaluated.isEmpty()) {
            verdict = "REVIEW";
        } else {
            verdict = "PASS";
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("orderId", order.getAmazonOrderId() != null ? order.getAmazonOrderId() : order.getId());
        result.put("shopId", shopId);
        result.put("verdict", verdict);
        result.put("alerts", alerts);
        result.put("actions", actions);
        result.put("alertCount", alerts.size());
        result.put("unevaluatedRules", unevaluated);
        result.put("unevaluatedCount", unevaluated.size());
        if (!advisoryActions.isEmpty()) {
            log.warn("订单 {} 命中建议类动作 {}：审单不会真的合并/拆分订单，也不会写 amz_order_split_log",
                    result.get("orderId"), advisoryActions);
            result.put("advisoryActions", advisoryActions);
            result.put("advisoryNote", "MERGE/SPLIT 只是规则建议：本系统没有合并/拆单实现，"
                    + "amz_order_split_log 全仓没有任何插入点，订单数据不会被这条规则改变。");
        }
        result.put("auditTime", LocalDateTime.now().toString());
        return result;
    }

    /**
     * 单条规则的判定结果。{@code matched == null} 表示"无法判定"，
     * 它与"判定为不匹配"必须可区分，否则任何评估故障都会伪装成"没有风险"。
     */
    private record Outcome(Boolean matched, String reason) {
        static Outcome evaluated(boolean matched) {
            return new Outcome(matched, null);
        }

        static Outcome unevaluable(String reason) {
            return new Outcome(null, reason);
        }

        boolean evaluated() {
            return matched != null;
        }
    }

    private static Map<String, Object> unevaluatedEntry(OrderAuditRule rule, String reason) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("ruleId", rule.getId());
        entry.put("ruleName", rule.getRuleName());
        entry.put("action", rule.getAction());
        entry.put("conditionField", rule.getConditionField());
        entry.put("conditionOp", rule.getConditionOp());
        entry.put("reason", reason);
        return entry;
    }

    @Override
    public List<Map<String, Object>> batchAudit(Long shopId, List<Order> orders) {
        return orders.stream().map(o -> auditOrder(shopId, o)).collect(Collectors.toList());
    }

    /**
     * 评估单条规则。返回 {@link Outcome}：无法判定 ≠ 判定为不匹配。
     */
    private Outcome evaluateCondition(OrderAuditRule rule, Order order) {
        String fieldValue = extractFieldValue(rule.getConditionField(), order);
        if (fieldValue == null) {
            return Outcome.unevaluable("条件字段 " + rule.getConditionField() + " 取不到值（字段未接入或订单该值为空）");
        }
        String expected = rule.getConditionValue();
        String op = rule.getConditionOp() == null ? "" : rule.getConditionOp();

        switch (op) {
            case "EQ":
                return expected != null
                        ? Outcome.evaluated(fieldValue.equalsIgnoreCase(expected)) : missingConditionValue(rule);
            case "NEQ":
                return expected != null
                        ? Outcome.evaluated(!fieldValue.equalsIgnoreCase(expected)) : missingConditionValue(rule);
            case "CONTAINS":
                return expected != null
                        ? Outcome.evaluated(fieldValue.toUpperCase().contains(expected.toUpperCase()))
                        : missingConditionValue(rule);
            case "GT":
            case "LT":
            case "GTE":
            case "LTE":
                return compareNumeric(op, fieldValue, expected, rule);
            case "REGEX":
                return safeRegexMatch(expected, fieldValue, rule);
            default:
                return Outcome.unevaluable("未知操作符 " + op);
        }
    }

    private static Outcome missingConditionValue(OrderAuditRule rule) {
        return Outcome.unevaluable("操作符 " + rule.getConditionOp() + " 缺少比较值 conditionValue");
    }

    private static Outcome compareNumeric(String op, String fieldValue, String expected, OrderAuditRule rule) {
        if (expected == null) {
            return missingConditionValue(rule);
        }
        try {
            int cmp = new BigDecimal(fieldValue).compareTo(new BigDecimal(expected));
            return switch (op) {
                case "GT" -> Outcome.evaluated(cmp > 0);
                case "LT" -> Outcome.evaluated(cmp < 0);
                case "GTE" -> Outcome.evaluated(cmp >= 0);
                default -> Outcome.evaluated(cmp <= 0);
            };
        } catch (NumberFormatException e) {
            // 旧实现折成 false：字段或规则值不是数字时，"金额>500 拦截"这类规则会静默失效
            return Outcome.unevaluable("数值比较失败，field=" + fieldValue + " conditionValue=" + expected);
        }
    }

    /**
     * 编译结果缓存上限。规则正则由店铺管理员提供，无界缓存等于给他们开一条
     * 内存放大通道（每条规则一串 Pattern），因此用访问序 LRU 封顶。
     */
    private static final int PATTERN_CACHE_MAX = 64;

    private static final Map<String, Pattern> PATTERN_CACHE = Collections.synchronizedMap(
            new LinkedHashMap<String, Pattern>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Pattern> eldest) {
                    return size() > PATTERN_CACHE_MAX;
                }
            });

    /**
     * 同一批订单会反复用同一组规则正则；{@code Pattern.compile} 每次都要重新解析
     * 并构建字符类/节点数组，属于纯粹的重复工作。语法错误不在这里吞：
     * 失败的编译不会进缓存，下一次仍然抛出，由调用方按"无法判定"处理。
     */
    static Pattern compiledPattern(String pattern) {
        return PATTERN_CACHE.computeIfAbsent(pattern, Pattern::compile);
    }

    /** 仅供测试与排障查看缓存规模。 */
    static int patternCacheSize() {
        return PATTERN_CACHE.size();
    }

    /** 仅供测试读取上限，避免用例里再抄一份 64。 */
    static int patternCacheMax() {
        return PATTERN_CACHE_MAX;
    }

    /**
     * 正则匹配专用线程池：与 ForkJoinPool.commonPool 隔离。
     * 旧实现跑在 commonPool 上，超时仅放弃等待并不取消任务，
     * ReDoS 回溯线程会持续占用 JVM 公共池，殃及并行流等其他组件。
     */
    private static final ExecutorService REGEX_EXECUTOR = Executors.newFixedThreadPool(2, r -> {
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
     * <p>
     * 超时 / 语法错误 / 池排队都返回"无法判定"而不是 false：线程池只有 2 个 worker，
     * 批量审单时排队任务会在还没开跑之前就撞满 500ms，旧实把它折成"不匹配"，
     * 等于高峰时段 BLOCK 类规则整体失效。
     */
    private Outcome safeRegexMatch(String pattern, String fieldValue, OrderAuditRule rule) {
        if (pattern == null) {
            return missingConditionValue(rule);
        }
        if (pattern.length() > 256) {
            return Outcome.unevaluable("正则超长（" + pattern.length() + " > 256），拒绝执行");
        }
        Future<Boolean> future = null;
        try {
            Pattern compiled = compiledPattern(pattern);
            future = REGEX_EXECUTOR.submit(() -> compiled.matcher(fieldValue).find());
            return Outcome.evaluated(future.get(500, TimeUnit.MILLISECONDS));
        } catch (TimeoutException e) {
            return Outcome.unevaluable("正则匹配超时 500ms（疑似灾难性回溯或专用线程池排队）");
        } catch (PatternSyntaxException e) {
            return Outcome.unevaluable("正则语法错误：" + e.getDescription());
        } catch (Exception e) {
            return Outcome.unevaluable("正则匹配异常：" + e.getClass().getSimpleName());
        } finally {
            if (future != null) {
                // 无论成功失败都尝试中断：超时的回溯任务不应继续占用线程
                future.cancel(true);
            }
        }
    }

    /**
     * 从订单对象提取条件字段值。返回 {@code null} 表示"这个字段取不到值"，
     * 与"取到空串"必须可区分：前者是规则无法判定，后者是真实的空值参与比较。
     */
    private String extractFieldValue(String field, Order order) {
        if (field == null) return null;
        switch (field) {
            case "shipping_address":
                // Order 模型没有地址字段（需从 OrderAttribute 或 SP-API getOrder 扩展后读入）。
                // 旧实现返回 ""：于是任何"地址黑名单/正则"规则都在拿空串比较并必然判为不匹配，
                // 店铺以为自己在拦，实际一条都没拦，而且日志无痕。
                return null;
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
                // 字段名写错或已下线：不能当成"空值"参与比较，否则规则静默失效
                return null;
        }
    }

    // ==================== 发货路由 ====================

    @Override
    public ShipmentRouting routeOrder(Long shopId, String amazonOrderId, String sku, String asin,
                                       Integer quantity, String country) {
        // 简化的默认路由：FBA优先 → 海外仓 → 本地仓
        ShipmentRouting routing = new ShipmentRouting();
        routing.setShopId(shopId);
        routing.setAmazonOrderId(amazonOrderId);
        routing.setSku(sku);
        routing.setAsin(asin);
        routing.setQuantity(quantity);

        // 默认策略：FBA 覆盖国家用 FBA，否则海外仓。这里能判定的只有「仓库类型」：
        // 仓库主数据 amz_warehouse 属于物流模块，订单模块没有它的 mapper 也没有到物流服务的
        // Feign 通道，所以拿不到任何真实仓库。旧实现用 country 拼出 "US-FBA-Warehouse"
        // 这样的字符串并 insert 进 amz_shipment_routing，等于把不存在的仓名写成发货依据。
        boolean isFbaCountry = country != null && (country.equals("US") || country.equals("CA")
                || country.equals("MX") || country.equals("GB") || country.equals("DE")
                || country.equals("FR") || country.equals("IT") || country.equals("ES")
                || country.equals("JP") || country.equals("AU"));
        if (isFbaCountry) {
            routing.setWarehouseType("FBA");
            routing.setSelectedReason("FBA主配送国家，仅仓库类型建议；具体发货仓未解析，需物流模块确认");
        } else {
            routing.setWarehouseType("OVERSEAS");
            routing.setSelectedReason("非FBA覆盖国家，走海外仓自发货；具体发货仓未解析，需物流模块确认");
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
