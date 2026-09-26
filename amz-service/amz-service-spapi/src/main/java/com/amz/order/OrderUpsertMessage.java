package com.amz.order;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

/**
 * 订单 upsert 消息体（SP-API -> amz-service-order）。
 * <p>
 * 字段与既有 {@code OrderSyncScheduler} 发出的保存消息保持兼容
 * （{@code buyerInfo.buyerName} 嵌套结构不变），消费端 {@code OrderConsumer}
 * 不用改就能继续解析；新增的 {@code source} / {@code eventTime} / {@code idempotencyKey}
 * 供后续按「较新事件才覆盖」的 upsert 语义使用。
 * <p>
 * 金额与日期一律以字符串传输：Gson double 会引入二进制浮点尘埃，
 * 消费端用 {@code new BigDecimal(String)} 精确落库。
 *
 * @param shopId             归属店铺；必填
 * @param amazonOrderId      Amazon 订单号；必填
 * @param eventTime          业务事件时间（乱序判断依据）
 * @param idempotencyKey     幂等键：shopId + amazonOrderId + eventTime + 通知类型
 */
public record OrderUpsertMessage(Long shopId,
                                 String marketplaceId,
                                 String region,
                                 String amazonOrderId,
                                 String orderStatus,
                                 LocalDateTime purchaseDate,
                                 LocalDateTime lastUpdateDate,
                                 String fulfillmentChannel,
                                 String shipServiceLevel,
                                 String currency,
                                 String orderTotal,
                                 String buyerName,
                                 LocalDateTime eventTime,
                                 String notificationType,
                                 String idempotencyKey) {

    /** 消息来源标记：定时同步。 */
    public static final String SOURCE_SYNC = "SPAPI_SYNC";

    /** 消息来源标记：通知驱动。 */
    public static final String SOURCE_NOTIFICATION = "SPAPI_NOTIFICATION";

    public OrderUpsertMessage {
        if (shopId == null) {
            throw new IllegalArgumentException("shopId 不允许为 null");
        }
        if (amazonOrderId == null || amazonOrderId.isBlank()) {
            throw new IllegalArgumentException("amazonOrderId 不允许为空");
        }
    }

    /** 供 MQ 消费端解析的字段映射。 */
    public Map<String, Object> toBodyMap() {
        Map<String, Object> body = new HashMap<>();
        body.put("amazonOrderId", amazonOrderId);
        body.put("shopId", shopId);
        body.put("marketplaceId", marketplaceId);
        body.put("region", region);
        body.put("purchaseDate", format(purchaseDate));
        body.put("lastUpdateDate", format(lastUpdateDate));
        body.put("eventTime", format(eventTime));
        body.put("orderStatus", orderStatus);
        body.put("fulfillmentChannel", fulfillmentChannel);
        body.put("shipServiceLevel", shipServiceLevel);
        body.put("orderTotal", orderTotal);
        body.put("currency", currency);
        body.put("notificationType", notificationType);
        body.put("idempotencyKey", idempotencyKey);
        body.put("source", eventTime == null ? SOURCE_SYNC : SOURCE_NOTIFICATION);
        Map<String, Object> buyerInfo = new HashMap<>();
        if (buyerName != null) {
            buyerInfo.put("buyerName", buyerName);
        }
        body.put("buyerInfo", buyerInfo);
        return body;
    }

    private static String format(LocalDateTime time) {
        return time == null ? null : time.toInstant(ZoneOffset.UTC).toString();
    }

    /** 金额以精确小数串输出，禁止 double 中转。 */
    static String amountToString(BigDecimal amount) {
        return amount == null ? null : amount.toPlainString();
    }

    /** 保留 ISO-8601 格式化入口，供测试与日志核对。 */
    static String iso(LocalDateTime time) {
        return time == null ? null : DateTimeFormatter.ISO_INSTANT.format(time.toInstant(ZoneOffset.UTC));
    }
}