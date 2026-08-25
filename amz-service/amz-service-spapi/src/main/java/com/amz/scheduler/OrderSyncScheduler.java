package com.amz.scheduler;

import com.amz.client.OrdersClient;
import com.amz.constant.MqConstant;
import com.amz.credential.ShopCredential;
import com.amz.credential.ShopCredentialStore;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

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

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private DistributedJobLock distributedJobLock;

    /** 发布去重（每订单 14 天 TTL）。 */
    @Autowired
    private org.springframework.data.redis.core.StringRedisTemplate stringRedisTemplate;

    private final Gson gson = new Gson();

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
            String region = ordersClient.mapMarketplaceToRegion(marketplaceId);
            try {
                List<JsonObject> orders = ordersClient.fetchOrders(
                        shopId, marketplaceId, createdAfter, DEFAULT_ORDER_STATUSES);
                log.info("syncOrders shopId={} fetched={} orders", shopId, orders.size());

                for (JsonObject order : orders) {
                    String amazonOrderId = optString(order, "AmazonOrderId");
                    if (amazonOrderId == null || amazonOrderId.isEmpty()) {
                        continue;
                    }
                    // Redis 发布去重：7 天窗口 × 15 分钟轮询会让同一订单存活期被重复发布
                    // ~600 次（放大 MQ 流量并迫使消费端做大量无效幂等查询）。
                    // SETNX+TTL 14 天保证每订单只发布一轮；失败时降级为不去重（宁可重复，
                    // 由下游唯一索引兜底，也不丢订单）
                    String dedupeKey = "amz:sync:published:" + shopId + ":" + amazonOrderId;
                    Boolean firstTime;
                    try {
                        firstTime = stringRedisTemplate.opsForValue()
                                .setIfAbsent(dedupeKey, "1", java.time.Duration.ofDays(14));
                    } catch (Exception redisEx) {
                        log.warn("发布去重 Redis 异常，本轮降级为不去重：{}", redisEx.getMessage());
                        firstTime = true;
                    }
                    if (Boolean.FALSE.equals(firstTime)) {
                        continue;
                    }

                    // 行级明细仅对新订单拉取一次（orderItems 端点配额紧）
                    List<JsonObject> orderItems = ordersClient.fetchOrderItems(
                            shopId, marketplaceId, amazonOrderId);

                    publishSaveMessage(shopId, marketplaceId, region, order);
                    publishProfitMessages(shopId, region, order, orderItems);
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
     * 注：SP-API Orders 列表接口不返回行级 orderItems，需另调 orderItems 接口拉取，
     * 此处 orderItems 暂为空数组，由 order 服务后续补全。
     */
    private void publishSaveMessage(Long shopId, String marketplaceId, String region, JsonObject order) {
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
        body.put("orderItems", List.of());

        sendJson(MqConstant.SAVE_ORDER_EXCHANGE, "", body);
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
            BigDecimalHolder revenue = parseAmount(orderTotal);
            publishSingleProfit(shopId, region, amazonOrderId, amazonOrderId,
                    revenue.value, null);
            return;
        }

        for (JsonObject item : orderItems) {
            String sku = optString(item, "SellerSku");
            JsonElement priceEl = item.get("ItemPrice");
            double lineRevenue = 0.0;
            if (priceEl != null && priceEl.isJsonObject()) {
                BigDecimalHolder line = parseAmount(priceEl.getAsJsonObject());
                lineRevenue = line.value;
            } else {
                // 无行金额时用 UnitPrice × Quantity 估算
                double unit = 0.0;
                int qty = item.has("Quantity") && !item.get("Quantity").isJsonNull()
                        ? item.get("Quantity").getAsInt() : 1;
                JsonElement unitEl = item.get("UnitPrice");
                if (unitEl != null && !unitEl.isJsonNull()) {
                    try { unit = unitEl.getAsDouble(); } catch (Exception ignore) { }
                }
                lineRevenue = unit * qty;
            }
            publishSingleProfit(shopId, region, amazonOrderId,
                    sku != null ? sku : amazonOrderId, lineRevenue, null);
        }
    }

    /**
     * 发布单条利润核算消息。
     */
    private void publishSingleProfit(Long shopId, String region, String amazonOrderId,
                                     String sku, double revenue, Object category) {
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

        sendJson(MqConstant.PROFIT_EXCHANGE, MqConstant.PROFIT_ROUTING_KEY, body);
    }

    /**
     * 将 body 序列化为 JSON 并通过 RabbitMQ 发送。
     * 设置 messageId（UUID）便于消费端在缺少业务标识时做幂等 fallback。
     * 使用 text/plain 内容类型，消费端按 String/原始字节解析。
     */
    private void sendJson(String exchange, String routingKey, Map<String, Object> body) {
        try {
            String json = gson.toJson(body);
            Message message = MessageBuilder
                    .withBody(json.getBytes(StandardCharsets.UTF_8))
                    .setContentType(MessageProperties.CONTENT_TYPE_TEXT_PLAIN)
                    .setMessageId(UUID.randomUUID().toString())
                    .build();
            rabbitTemplate.send(exchange, routingKey, message);
        } catch (Exception e) {
            log.error("sendJson failed exchange={} routingKey={} body={}", exchange, routingKey, body, e);
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
     * 解析 OrderTotal.Amount 为 double；失败返回 0。
     */
    private BigDecimalHolder parseAmount(JsonObject orderTotal) {
        if (orderTotal == null) {
            return new BigDecimalHolder(0.0);
        }
        JsonElement amountEl = orderTotal.get("Amount");
        if (amountEl == null || amountEl.isJsonNull()) {
            return new BigDecimalHolder(0.0);
        }
        try {
            return new BigDecimalHolder(amountEl.getAsDouble());
        } catch (NumberFormatException e) {
            return new BigDecimalHolder(0.0);
        }
    }

    /**
     * 简单的金额持有者（避免在 Gson 序列化时丢失精度信息，统一用 double 传输）。
     */
    private record BigDecimalHolder(double value) {
    }
}
