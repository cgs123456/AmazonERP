package com.amz.scheduler;

import com.amz.client.OrdersClient;
import com.amz.constant.MqConstant;
import com.amz.credential.ShopCredential;
import com.amz.credential.ShopCredentialStore;
import com.amz.connector.MarketplaceRegistry;
import com.amz.connector.SpApiEndpointResolver;
import com.amz.lock.DistributedJobLock;
import com.amz.order.OrderUpsertPublisher;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 订单定时同步调度器。
 * <p>
 * 每 15 分钟轮询一次活跃店铺，从 SP-API 拉取最近 7 天订单：
 * 1. 构造订单保存消息发送到 {@link MqConstant#SAVE_ORDER_EXCHANGE}，由 order 服务消费落库；
 * 2. 构造利润核算消息发送到 {@link MqConstant#PROFIT_EXCHANGE}，由 ProfitMQConsumer 消费计算利润。
 * <p>
 * 单店失败不影响其他店铺同步。
 */
@Component
@Profile("!bootstrap")

public class OrderSyncScheduler {

    private static final Logger log = LoggerFactory.getLogger(OrderSyncScheduler.class);

    /**
     * 同步时间窗口：最近 7 天。
     */
    private static final long SYNC_WINDOW_SECONDS = 7L * 24 * 3600L;

    /**
     * 默认拉取的订单状态。
     */
    private static final List<String> DEFAULT_ORDER_STATUSES =
            List.of("Shipped", "PartiallyShipped", "Unshipped");

    @Autowired
    private ShopCredentialStore shopCredentialStore;

    @Autowired
    private OrdersClient ordersClient;

    /**
     * 订单 PII 同步开关，默认关闭。开启后 Orders 与 OrderItems 都只走 RDT；
     * 受限令牌失败时该店铺本轮直接失败，绝不回退普通 LWA 请求。
     */
    @Value("${spapi.orders.pii-sync-enabled:false}")
    private boolean orderPiiSyncEnabled;

    /**
     * P0-51：端点覆盖生效时，定时同步必须跳过——桩数据不得经 MQ 进入业务表。
     */
    @Autowired
    private SpApiEndpointResolver spApiEndpointResolver;

    /**
     * 订单消息发送器。
     * <p>
     * 为什么不再就地 new 一份发送逻辑：旧实现把发送异常 catch 住只打一行日志，
     * 调用方看到的是「同步成功」——MQ 宕机时整条链路静默丢单，界面上订单数不涨，
     * 与「真的没有新订单」无法区分。这里发送失败直接抛
     * {@link com.amz.order.OrderUpsertPublishException}，由调用方决定告警与重试。
     */
    @Autowired
    private OrderUpsertPublisher orderUpsertPublisher;

    @Autowired
    private DistributedJobLock distributedJobLock;

    /** 发布去重（每订单 14 天 TTL）。 */
    @Autowired
    private org.springframework.data.redis.core.StringRedisTemplate stringRedisTemplate;

    /**
     * 每 15 分钟执行一次（上一次执行结束后起算 fixedDelay）。
     * 分布式锁互斥：多实例部署时仅一个实例拉取，避免双倍消耗 SP-API 配额与重复发消息。
     * 租期 14 分钟略小于 15 分钟调度周期，实例崩溃后下一轮可正常接手。
     */
    @Scheduled(fixedDelay = 15 * 60 * 1000)
    public void syncOrders() {
        distributedJobLock.runWithLock(
                "amz:sched:order-sync",
                14 * 60L,
                this::doSyncOrders);
    }

    private void doSyncOrders() {
        if (spApiEndpointResolver != null && spApiEndpointResolver.isOverrideActive()) {
            log.warn("syncOrders skipped: {}={} 生效（仅限非生产的桩/联调地址）。"
                            + "为避免桩数据经 MQ 进入业务表，本轮不调用平台也不落库；恢复真实同步请清空该配置。",
                    SpApiEndpointResolver.OVERRIDE_PROPERTY, spApiEndpointResolver.baseUrlOverride());
            return;
        }
        Set<Long> shopIds = shopCredentialStore.getActiveShopIds();
        if (shopIds.isEmpty()) {
            log.info("syncOrders: no active shops, skipping");
            return;
        }

        Instant createdAfter = Instant.now().minusSeconds(SYNC_WINDOW_SECONDS);
        log.info("syncOrders start: shopCount={} createdAfter={}", shopIds.size(), createdAfter);

        for (Long shopId : shopIds) {
            ShopCredential credential = shopCredentialStore.get(shopId);
            if (credential == null || credential.getMarketplaceId() == null) {
                log.warn("syncOrders skip shopId={}: credential or marketplaceId missing", shopId);
                continue;
            }
            String marketplaceId = credential.getMarketplaceId();
            // P0-36：映射统一走单一事实源；未登记 marketplaceId 会抛异常（由外层 catch 记录到该店铺）
            String region = MarketplaceRegistry.resolveRegion(marketplaceId);
            try {
                List<JsonObject> orders = orderPiiSyncEnabled
                        ? ordersClient.fetchOrdersWithRestrictedData(
                        shopId, marketplaceId, createdAfter, DEFAULT_ORDER_STATUSES)
                        : ordersClient.fetchOrders(
                        shopId, marketplaceId, createdAfter, DEFAULT_ORDER_STATUSES);
                log.info("syncOrders shopId={} fetched={} orders", shopId, orders.size());

                for (JsonObject order : orders) {
                    String amazonOrderId = optString(order, "AmazonOrderId");
                    if (amazonOrderId == null || amazonOrderId.isEmpty()) {
                        continue;
                    }
                    // Redis 发布去重：7 天窗口 × 15 分钟轮询会让同一订单存活期被重复发布
                    // ~600 次（放大 MQ 流量并迫使消费端做大量无效幂等查询）。
                    // 顺序很关键：先发消息、成功后才标记（见 markPublished）。
                    // 旧实现先 SETNX 标记再发送，MQ 短暂故障时订单会被永久判成「已发布」，
                    // 14 天 TTL 内不再重发 —— 静默丢单，且界面上与「没有新订单」无法区分。
                    String dedupeKey = "amz:sync:published:" + shopId + ":" + amazonOrderId;
                    if (isPublished(dedupeKey)) {
                        continue;
                    }

                    // 行级明细仅对新订单拉取一次（orderItems 端点配额紧）
                    List<JsonObject> orderItems = orderPiiSyncEnabled
                            ? ordersClient.fetchOrderItemsWithRestrictedData(
                            shopId, marketplaceId, amazonOrderId)
                            : ordersClient.fetchOrderItems(
                            shopId, marketplaceId, amazonOrderId);

                    publishSaveMessage(shopId, marketplaceId, region, order, orderItems);
                    publishProfitMessages(shopId, region, order, orderItems);
                    // 两条消息都发出成功才标记；任一条发送失败会抛异常，
                    // 本轮该店铺中止并告警，未标记则下一轮自动重发
                    markPublished(dedupeKey);
                }
            } catch (Exception e) {
                log.error("syncOrders failed shopId={}", shopId, e);
            }
        }
        log.info("syncOrders done");
    }

    /**
     * 构造订单保存消息（JSON），发送到 order 服务的 save 交换机。
     * <p>
     * 消息体包含：amazonOrderId, shopId, marketplaceId, buyerInfo, orderItems,
     * orderTotal, currency, purchaseDate, fulfillmentChannel, shipServiceLevel。
     * <p>
     * 注：SP-API Orders 列表接口不返回行级 orderItems，需另调 orderItems 接口拉取；
     * 本方法接收已拉取的 orderItems 并随保存消息一起下发，由 order 服务落库到
     * amz_order_item（V5）。拉取失败时上游会抛异常、不会走到这里，因此这里的
     * 空列表只表示“该订单确实没有行”（异常场景不会伪装成空）。
     */
    private void publishSaveMessage(Long shopId, String marketplaceId, String region, JsonObject order,
                                     List<JsonObject> orderItems) {
        Map<String, Object> body = new HashMap<>();
        body.put("amazonOrderId", optString(order, "AmazonOrderId"));
        body.put("shopId", shopId);
        body.put("marketplaceId", marketplaceId);
        body.put("region", region);
        body.put("purchaseDate", optString(order, "PurchaseDate"));
        body.put("lastUpdateDate", optString(order, "LastUpdateDate"));
        body.put("orderStatus", optString(order, "OrderStatus"));
        body.put("fulfillmentChannel", optString(order, "FulfillmentChannel"));
        body.put("shipServiceLevel", optString(order, "ShipServiceLevel"));

        JsonObject orderTotal = optObject(order, "OrderTotal");
        body.put("orderTotal", orderTotal != null ? optString(orderTotal, "Amount") : null);
        body.put("currency", orderTotal != null ? optString(orderTotal, "CurrencyCode") : null);

        JsonObject buyerInfo = optObject(order, "BuyerInfo");
        Map<String, Object> buyerMap = new HashMap<>();
        if (buyerInfo != null) {
            buyerMap.put("buyerEmail", optString(buyerInfo, "BuyerEmail"));
            buyerMap.put("buyerName", optString(buyerInfo, "BuyerName"));
            buyerMap.put("buyerCounty", optString(buyerInfo, "BuyerCounty"));
        }
        body.put("buyerInfo", buyerMap);
        body.put("orderItems", toItemMaps(orderItems, optString(order, "FulfillmentChannel")));

        orderUpsertPublisher.sendJson(MqConstant.SAVE_ORDER_EXCHANGE, "", body);
    }

    /**
     * 把 SP-API OrderItem 列表压成可序列化的 Map 列表，字段名对齐
     * order 服务 OrderConsumer 的解析键（camelCase）。
     * <p>
     * 只搬确定存在的官方字段：ASIN / SellerSKU / OrderItemId / Title / QuantityOrdered /
     * ItemPrice / ItemTax / PromotionDiscount。不认识的字段不猜测、不补默认值。
     */
    private List<Map<String, Object>> toItemMaps(List<JsonObject> orderItems, String orderChannel) {
        List<Map<String, Object>> items = new ArrayList<>();
        if (orderItems == null || orderItems.isEmpty()) {
            return items;
        }
        for (JsonObject item : orderItems) {
            Map<String, Object> m = new HashMap<>();
            m.put("amazonOrderItemId", optString(item, "OrderItemId"));
            m.put("asin", optString(item, "ASIN"));
            m.put("sellerSku", optString(item, "SellerSKU"));
            m.put("title", optString(item, "Title"));
            m.put("quantity", optInt(item, "QuantityOrdered"));
            JsonObject itemPrice = optObject(item, "ItemPrice");
            m.put("itemPrice", itemPrice != null ? optString(itemPrice, "Amount") : null);
            m.put("currency", itemPrice != null ? optString(itemPrice, "CurrencyCode") : null);
            JsonObject itemTax = optObject(item, "ItemTax");
            m.put("itemTax", itemTax != null ? optString(itemTax, "Amount") : null);
            JsonObject promotion = optObject(item, "PromotionDiscount");
            m.put("promotionDiscount", promotion != null ? optString(promotion, "Amount") : null);
            // 配送渠道是订单级属性，明细行没有该字段时回退订单级取值
            m.put("fulfillmentChannel",
                    optString(item, "FulfillmentChannel") != null
                            ? optString(item, "FulfillmentChannel") : orderChannel);
            items.add(m);
        }
        return items;
    }

    private int optInt(JsonObject obj, String key) {
        if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) {
            return 0;
        }
        try {
            return obj.get(key).getAsInt();
        } catch (NumberFormatException | UnsupportedOperationException e) {
            return 0;
        }
    }

    /**
     * 构造利润核算消息（按行级明细拆分），发送到 profit 交换机。
     * <p>
     * 有行级明细时：每个 orderItem 发布一条消息，sku 使用真实 sellerSku、
     * revenue 使用行金额（itemPrice × quantity 的 SP-API LineAmount）——
     * 使下游 COGS/佣金率可按真实 SKU 命中（旧实现 sku=amazonOrderId 恒缺失）。
     * 明细缺失/为空时：回退订单级聚合消息（sku=amazonOrderId），保证不丢核算。
     */
    private void publishProfitMessages(Long shopId, String region, JsonObject order,
                                       List<JsonObject> orderItems) {
        String amazonOrderId = optString(order, "AmazonOrderId");
        JsonObject orderTotal = optObject(order, "OrderTotal");

        if (orderItems == null || orderItems.isEmpty()) {
            String revenue = parseAmount(orderTotal);
            publishSingleProfit(shopId, region, amazonOrderId, amazonOrderId,
                    revenue, null);
            return;
        }

        for (JsonObject item : orderItems) {
            String sku = optString(item, "SellerSku");
            JsonElement priceEl = item.get("ItemPrice");
            String lineRevenue;
            if (priceEl != null && priceEl.isJsonObject()) {
                lineRevenue = parseAmount(priceEl.getAsJsonObject());
            } else {
                // 无行金额时用 UnitPrice × Quantity 估算（字符串精确运算，禁止 double 中转）
                int qty = item.has("Quantity") && !item.get("Quantity").isJsonNull()
                        ? item.get("Quantity").getAsInt() : 1;
                JsonElement unitEl = item.get("UnitPrice");
                String unitStr = (unitEl != null && unitEl.isJsonPrimitive()) ? unitEl.getAsString() : null;
                lineRevenue = multiply(unitStr, qty);
            }
            publishSingleProfit(shopId, region, amazonOrderId,
                    sku != null ? sku : amazonOrderId, lineRevenue, null);
        }
    }

    /**
     * 发布单条利润核算消息。
     */
    private void publishSingleProfit(Long shopId, String region, String amazonOrderId,
                                     String sku, String revenue, Object category) {
        Map<String, Object> body = new HashMap<>();
        body.put("shopId", shopId);
        body.put("amazonOrderId", amazonOrderId);
        // 行级 SKU（或回退的订单级标识），满足 profit_report sku NOT NULL 约束
        body.put("sku", sku);
        body.put("revenue", revenue);
        body.put("category", category);
        body.put("sizeTier", null);
        body.put("weightG", null);
        body.put("region", region);

        orderUpsertPublisher.sendJson(MqConstant.PROFIT_EXCHANGE, MqConstant.PROFIT_ROUTING_KEY, body);
    }

    /**
     * 该订单此前是否已成功发布过。
     * <p>
     * Redis 不可用时按「未发布」处理：宁可重复发布（下游订单按唯一键幂等、
     * 利润按 shopId+amazonOrderId+sku 去重），也不冒丢单的风险。
     */
    private boolean isPublished(String dedupeKey) {
        try {
            return Boolean.TRUE.equals(stringRedisTemplate.hasKey(dedupeKey));
        } catch (Exception redisEx) {
            log.warn("发布去重查询 Redis 异常，按未发布处理（可能重复发布，由下游幂等吸收）：{}",
                    redisEx.getMessage());
            return false;
        }
    }

    /**
     * 标记该订单已发布（仅在消息发送成功后调用）。
     * <p>
     * 用 SETNX + 14 天 TTL：并发实例下谁先发谁标记，已存在则不重置 TTL；
     * 标记失败只告警——最坏结果是下一轮重复发布，而不是丢单。
     */
    private void markPublished(String dedupeKey) {
        try {
            stringRedisTemplate.opsForValue()
                    .setIfAbsent(dedupeKey, "1", Duration.ofDays(14));
        } catch (Exception redisEx) {
            log.warn("发布去重标记失败，下一轮可能重复发布（由下游幂等吸收）：{}",
                    redisEx.getMessage());
        }
    }

    private String optString(JsonObject obj, String key) {
        if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) {
            return null;
        }
        return obj.get(key).getAsString();
    }

    private JsonObject optObject(JsonObject obj, String key) {
        if (obj == null || !obj.has(key) || !obj.get(key).isJsonObject()) {
            return null;
        }
        return obj.getAsJsonObject(key);
    }

    /**
     * 解析 OrderTotal.Amount 为精确小数串；失败返回 "0.00"。
     * <p>
     * 金额全程以字符串传输：Gson double 会引入二进制浮点尘埃（如 19.99*3=59.969999...），
     * 而 Gson 对 JSON 数字的 getAsString / Jackson 的 String→BigDecimal 都是精确的。
     * 消费端（ProfitMQConsumer）直接 new BigDecimal(String) 落库。
     */
    private String parseAmount(JsonObject orderTotal) {
        if (orderTotal == null) {
            return "0.00";
        }
        JsonElement amountEl = orderTotal.get("Amount");
        if (amountEl == null || amountEl.isJsonNull()) {
            return "0.00";
        }
        try {
            if (amountEl.isJsonPrimitive()) {
                JsonPrimitive primitive = amountEl.getAsJsonPrimitive();
                if (primitive.isNumber()) {
                    return primitive.getAsBigDecimal().toPlainString();
                }
                if (primitive.isString()) {
                    return new BigDecimal(primitive.getAsString().trim()).toPlainString();
                }
            }
            return "0.00";
        } catch (NumberFormatException | ArithmeticException e) {
            return "0.00";
        }
    }

    /**
     * 单价字符串 × 数量（精确运算，禁止 double 中转）。
     */
    private String multiply(String unitStr, int qty) {
        if (unitStr == null || unitStr.isBlank()) {
            return "0.00";
        }
        try {
            return new BigDecimal(unitStr.trim()).multiply(BigDecimal.valueOf(qty))
                    .setScale(2, RoundingMode.HALF_UP).toPlainString();
        } catch (NumberFormatException | ArithmeticException e) {
            return "0.00";
        }
    }
}
