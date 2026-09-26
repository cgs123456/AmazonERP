package com.amz.notification;

import com.amz.order.OrderUpsertMessage;
import com.amz.order.OrderUpsertPublisher;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * ORDER_CHANGE 处理器：把订单状态变化推送给 amz-service-order 做 upsert。
 * <p>
 * 为什么必须走 upsert 而不是「存在即跳过」：ORDER_CHANGE 通知的绝大部分价值
 * 就在于「已存在的订单状态变了」（Unshipped -> Shipped -> Canceled）。
 * 现有 {@code OrderServiceImpl.syncAmazonOrder} 先按 amazonOrderId 查，存在就直接 return，
 * 等于把通知里唯一有用的信息丢掉——订单表会永远停在首次同步时的状态。
 * <p>
 * 幂等键 {@code shopId + amazonOrderId + eventTime + 通知类型}：
 * SQS standard 队列会重复投递，但「同号同时刻」的事件语义上就是同一条，
 * 下游按此键去重即可，不必依赖消息中间件的一次性语义。
 */
@Slf4j
@Component
public class OrderChangeHandler implements NotificationEventHandler {

    /** 支持的通知类型。 */
    public static final String TYPE = "ORDER_CHANGE";

    /** Payload 内的业务节点名。 */
    public static final String PAYLOAD_NODE = "OrderChangeNotification";

    /** 正文缺少业务节点。 */
    public static final String ERR_MISSING_NODE = "MISSING_ORDER_CHANGE_NODE";
    /** 缺少订单号。 */
    public static final String ERR_MISSING_ORDER_ID = "MISSING_AMAZON_ORDER_ID";
    /** 缺少订单状态。 */
    public static final String ERR_MISSING_STATUS = "MISSING_ORDER_STATUS";
    /** 事件未归属店铺。 */
    public static final String ERR_UNRESOLVED_SHOP = "UNRESOLVED_SHOP";

    private final OrderUpsertPublisher publisher;

    public OrderChangeHandler(OrderUpsertPublisher publisher) {
        this.publisher = publisher;
    }

    @Override
    public boolean supports(String notificationType) {
        return TYPE.equalsIgnoreCase(notificationType);
    }

    @Override
    public void handle(NotificationContext ctx) {
        JsonNode node = ctx.payload() == null ? null : ctx.payload().get(PAYLOAD_NODE);
        if (node == null || !node.isObject()) {
            throw NotificationProcessingException.fatal(ERR_MISSING_NODE,
                    "ORDER_CHANGE 通知缺少 " + PAYLOAD_NODE + " 节点");
        }
        String amazonOrderId = text(node, "AmazonOrderId");
        if (amazonOrderId == null) {
            throw NotificationProcessingException.fatal(ERR_MISSING_ORDER_ID,
                    "ORDER_CHANGE 通知缺少 AmazonOrderId");
        }
        String orderStatus = text(node, "OrderStatus");
        if (orderStatus == null) {
            throw NotificationProcessingException.fatal(ERR_MISSING_STATUS,
                    "ORDER_CHANGE 通知缺少 OrderStatus");
        }
        if (ctx.shopId() == null) {
            throw NotificationProcessingException.terminal(
                    NotificationInboxStatus.UNRESOLVED_SUBSCRIPTION, ERR_UNRESOLVED_SHOP,
                    "ORDER_CHANGE 事件未归属店铺，补订阅映射后重放");
        }
        LocalDateTime eventTime = ctx.eventTime() == null ? LocalDateTime.now() : ctx.eventTime();
        String idempotencyKey = ctx.shopId() + ":" + amazonOrderId + ":" + eventTime + ":" + TYPE;

        publisher.publish(new OrderUpsertMessage(
                ctx.shopId(),
                ctx.marketplaceId(),
                null,
                amazonOrderId,
                orderStatus,
                null,
                eventTime,
                text(node, "FulfillmentChannel"),
                text(node, "ShipServiceLevel"),
                null,
                null,
                null,
                eventTime,
                TYPE,
                idempotencyKey));
        log.info("[OrderChangeHandler] 已发布订单 upsert：shopId={}, amazonOrderId={}, status={}, eventTime={}",
                ctx.shopId(), amazonOrderId, orderStatus, eventTime);
    }

    private static String text(JsonNode node, String field) {
        JsonNode n = node.get(field);
        if (n == null || n.isNull()) {
            return null;
        }
        String s = n.asText();
        return s == null || s.isBlank() ? null : s;
    }
}