package com.amz.mq.consumer;

import com.amz.constant.MqConstant;
import com.amz.model.dto.OrderDto;
import com.amz.model.dto.OrderSyncDto;
import com.amz.model.pojo.CustomAttribute;
import com.amz.service.OrderService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

/**
 * 订单保存消费者。
 * <p>
 * 消费两种来源的 JSON 消息：
 * 1. 购物车下单（OrderServiceImpl.saveOrder 发送，含 productId/userId/selectAttributes）；
 * 2. SP-API 同步订单（OrderSyncScheduler 发送，含 amazonOrderId/shopId 等）。
 * <p>
 * 幂等键：优先 amazonOrderId + shopId；缺失时用 AMQP messageId；再缺失用 deliveryTag 兜底。
 */
@Component
@Slf4j
public class OrderConsumer {

    @Autowired
    private OrderService orderService;

    @Autowired
    private ObjectMapper objectMapper;

    @RabbitListener(queues = MqConstant.SAVE_ORDER_QUEUE)
    public void onMessage(Message message,
                          Channel channel,
                          @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) {
        String rawBody = new String(message.getBody(), StandardCharsets.UTF_8);
        MessageProperties props = message.getMessageProperties();
        String amqpMessageId = props != null ? props.getMessageId() : null;

        try {
            JsonNode root = objectMapper.readTree(rawBody);

            String amazonOrderId = textOrNull(root, "amazonOrderId");
            JsonNode shopIdNode = root.get("shopId");
            String shopId = (shopIdNode != null && !shopIdNode.isNull()) ? shopIdNode.asText() : null;

            // 幂等键：优先 amazonOrderId + shopId；其次 AMQP messageId；最后 deliveryTag 兜底
            String messageId;
            if (amazonOrderId != null && !amazonOrderId.isEmpty()) {
                messageId = (shopId != null && !shopId.isEmpty()) ? amazonOrderId + ":" + shopId : amazonOrderId;
            } else if (amqpMessageId != null && !amqpMessageId.isEmpty()) {
                messageId = amqpMessageId;
            } else {
                messageId = String.valueOf(deliveryTag);
            }

            JsonNode userIdNode = root.get("userId");
            boolean hasUserId = userIdNode != null && !userIdNode.isNull();

            // SP-API 订单（有 amazonOrderId 但无 userId）：构造 OrderSyncDto 调用 syncAmazonOrder 落库
            if (amazonOrderId != null && !hasUserId) {
                OrderSyncDto syncDto = buildOrderSyncDto(root, amazonOrderId, shopId);
                log.info("OrderConsumer 处理 SP-API 订单消息：messageId={}, amazonOrderId={}", messageId, amazonOrderId);
                orderService.syncAmazonOrder(syncDto);
                ack(channel, deliveryTag);
                log.info("SP-API 订单消息已确认，落库完成，messageId={}", messageId);
                return;
            }

            OrderDto orderDto = new OrderDto();
            orderDto.setMessageId(messageId);
            if (hasUserId) {
                orderDto.setUserId(userIdNode.asInt());
            }
            JsonNode productIdNode = root.get("productId");
            if (productIdNode != null && !productIdNode.isNull()) {
                orderDto.setProductId(productIdNode.asInt());
            }
            JsonNode priceNode = root.get("price");
            if (priceNode != null && !priceNode.isNull()) {
                orderDto.setPrice(priceNode.decimalValue());
            }
            JsonNode attrsNode = root.get("selectAttributes");
            if (attrsNode != null && attrsNode.isArray() && attrsNode.size() > 0) {
                List<CustomAttribute> attrs = new ArrayList<>();
                for (JsonNode attrNode : attrsNode) {
                    attrs.add(objectMapper.treeToValue(attrNode, CustomAttribute.class));
                }
                orderDto.setSelectAttributes(attrs);
            }

            log.info("OrderConsumer 处理消息：messageId={}, body={}", messageId, rawBody);
            orderService.processOrderMessage(orderDto);

            ack(channel, deliveryTag);
            log.info("消息已确认，订单处理成功，messageId={}", messageId);
        } catch (IllegalStateException e) {
            // 幂等性跳过或参数校验失败：直接 ack，不重新入队
            log.warn("跳过消息处理（幂等或参数校验）：messageId={}, 错误: {}", amqpMessageId, e.getMessage());
            ack(channel, deliveryTag);
        } catch (Exception e) {
            log.error("处理订单消息异常，消息将重新入队：messageId={}", amqpMessageId, e);
            nack(channel, deliveryTag, true);
        }
    }

    private void ack(Channel channel, long deliveryTag) {
        try {
            channel.basicAck(deliveryTag, false);
        } catch (Exception ex) {
            log.error("basicAck 异常", ex);
        }
    }

    private void nack(Channel channel, long deliveryTag, boolean requeue) {
        try {
            channel.basicNack(deliveryTag, false, requeue);
        } catch (Exception ex) {
            log.error("basicNack 异常", ex);
        }
    }

    private String textOrNull(JsonNode node, String field) {
        JsonNode n = node.get(field);
        if (n == null || n.isNull()) {
            return null;
        }
        String s = n.asText();
        return (s == null || s.isEmpty()) ? null : s;
    }

    /**
     * 由 SP-API 同步消息构造 OrderSyncDto。
     * 消息字段对齐 OrderSyncScheduler.publishSaveMessage：buyerName 嵌套在 buyerInfo 内，
     * purchaseDate/lastUpdateDate 为 ISO 8601 带 Z 后缀字符串，orderTotal 为金额字符串。
     */
    private OrderSyncDto buildOrderSyncDto(JsonNode root, String amazonOrderId, String shopIdStr) {
        OrderSyncDto dto = new OrderSyncDto();
        dto.setAmazonOrderId(amazonOrderId);
        if (shopIdStr != null && !shopIdStr.isEmpty()) {
            try {
                dto.setShopId(Long.parseLong(shopIdStr));
            } catch (NumberFormatException e) {
                log.warn("SP-API 订单 shopId 解析失败：{}", shopIdStr);
            }
        }
        dto.setMarketplaceId(textOrNull(root, "marketplaceId"));
        dto.setOrderStatus(textOrNull(root, "orderStatus"));
        dto.setFulfillmentChannel(textOrNull(root, "fulfillmentChannel"));
        dto.setShipServiceLevel(textOrNull(root, "shipServiceLevel"));
        dto.setPurchaseDate(parseAmazonDate(textOrNull(root, "purchaseDate")));
        dto.setLastUpdateDate(parseAmazonDate(textOrNull(root, "lastUpdateDate")));
        dto.setCurrency(textOrNull(root, "currency"));

        String orderTotal = textOrNull(root, "orderTotal");
        if (orderTotal != null && !orderTotal.isEmpty()) {
            try {
                dto.setTotalAmount(new BigDecimal(orderTotal));
            } catch (NumberFormatException e) {
                log.warn("SP-API 订单 orderTotal 解析失败：{}", orderTotal);
            }
        }

        JsonNode buyerInfo = root.get("buyerInfo");
        if (buyerInfo != null && !buyerInfo.isNull()) {
            dto.setBuyerName(textOrNull(buyerInfo, "buyerName"));
        }
        return dto;
    }

    /**
     * 解析 Amazon SP-API ISO 8601 日期（如 2024-01-15T10:30:00Z）为 LocalDateTime（UTC）。
     */
    private LocalDateTime parseAmazonDate(String dateStr) {
        if (dateStr == null || dateStr.isEmpty()) {
            return null;
        }
        try {
            return Instant.parse(dateStr).atZone(ZoneOffset.UTC).toLocalDateTime();
        } catch (Exception e1) {
            try {
                return LocalDateTime.parse(dateStr);
            } catch (Exception e2) {
                log.warn("无法解析 Amazon 日期：{}", dateStr);
                return null;
            }
        }
    }
}
