package com.amz.notification;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 合成事件源（mock）。
 * <p>
 * 用途只有一个：在没有真实 Amazon 凭证的阶段，让通知入站的整条链路
 * （校验 -> 加密落库 -> 去重 -> 领取 -> Handler -> 订单 upsert）可以真实跑起来并留下回归证据。
 * <p>
 * 因此它必须满足两点：
 * <ol>
 *   <li><b>确定性</b>：同一 scenario 每次产出完全相同的内容，测试才能断言。</li>
 *   <li><b>可识别</b>：事件信封 {@code synthetic=true}，载荷里另带 {@code SyntheticMarker=SYNTHETIC}，
 *       并一路写入 Inbox 的 {@code synthetic} 列。演练数据进入生产业务表是不可接受的事故。</li>
 * </ol>
 * <p>
 * 事件流是有限长度的：取完后 {@code poll} 返回空列表，不会无限循环刷事件。
 */
@Slf4j
public class MockNotificationEventSource implements NotificationEventSource {

    /** 顶层合成标记字段名。 */
    public static final String SYNTHETIC_MARKER_FIELD = "SyntheticMarker";

    /** 顶层合成标记取值。 */
    public static final String SYNTHETIC_MARKER_VALUE = "SYNTHETIC";

    /** 合成事件的固定时间基准，保证确定性（不使用系统当前时间）。 */
    private static final OffsetDateTime BASE_TIME =
            OffsetDateTime.of(2026, 9, 20, 10, 0, 0, 0, ZoneOffset.UTC);

    private static final String APPLICATION_ID = "amzn1.sp.solution.00000000-0000-0000-0000-SYNTHETIC00";
    private static final String SUBSCRIPTION_ID = "SYNTHETIC-SUB-0001";
    private static final String DESTINATION_ID = "SYNTHETIC-DEST-0001";

    private final ObjectMapper objectMapper;
    private final List<NotificationEnvelope> events;
    private final AtomicInteger cursor = new AtomicInteger(0);
    private final java.util.Set<String> acknowledged = ConcurrentHashMap.newKeySet();

    public MockNotificationEventSource(ObjectMapper objectMapper, String scenario) {
        this.objectMapper = objectMapper;
        this.events = Collections.unmodifiableList(build(scenario));
        log.warn("[MockNotificationEventSource] 已装载合成事件源：scenario={}, 事件数={}。"
                        + "这些事件不是真实 Amazon 数据，禁止用于生产业务决策。",
                scenario, events.size());
    }

    @Override
    public List<NotificationEnvelope> poll(int maxMessages) {
        int limit = Math.max(1, maxMessages);
        List<NotificationEnvelope> batch = new ArrayList<>();
        while (batch.size() < limit) {
            int index = cursor.getAndIncrement();
            if (index >= events.size()) {
                break;
            }
            batch.add(events.get(index));
        }
        return batch;
    }

    @Override
    public void acknowledge(List<String> receiptHandles) {
        if (receiptHandles == null) {
            return;
        }
        acknowledged.addAll(receiptHandles);
    }

    @Override
    public String name() {
        return "mock";
    }

    /** 已 ack 的回执句柄（仅用于测试与演练核对）。 */
    public java.util.Set<String> acknowledgedHandles() {
        return Collections.unmodifiableSet(acknowledged);
    }

    /** 本 scenario 的事件总数。 */
    public int size() {
        return events.size();
    }

    private List<NotificationEnvelope> build(String scenario) {
        List<NotificationEnvelope> list = new ArrayList<>();
        String normalized = scenario == null || scenario.isBlank() ? "order-change" : scenario.trim();
        switch (normalized) {
            case "feed-report" -> {
                list.add(envelope(1, feedFinished(1)));
                list.add(envelope(2, reportFinished(1)));
            }
            case "unsupported" -> list.add(envelope(1, unsupported(1)));
            case "duplicate" -> {
                ObjectNode event = orderChange(1, "Shipped");
                list.add(envelope(1, event));
                // 同一 notificationId 再投递一次：用于验证 uk_notification_id 去重
                list.add(envelope(1, event));
            }
            case "mixed" -> {
                list.add(envelope(1, orderChange(1, "Unshipped")));
                list.add(envelope(2, orderChange(2, "Shipped")));
                list.add(envelope(3, feedFinished(2)));
                list.add(envelope(4, reportFinished(2)));
                list.add(envelope(5, unsupported(2)));
            }
            default -> {
                list.add(envelope(1, orderChange(1, "Unshipped")));
                list.add(envelope(2, orderChange(2, "Shipped")));
                list.add(envelope(3, orderChange(3, "Canceled")));
            }
        }
        return list;
    }

    private NotificationEnvelope envelope(int seq, ObjectNode event) {
        String json = event.toString();
        return new NotificationEnvelope("mock-" + seq, json, true);
    }

    private ObjectNode envelope(int seq, String type, String payloadVersion, ObjectNode payload,
                                String notificationId) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("NotificationVersion", "2022-02-02");
        root.put("NotificationType", type);
        root.put("PayloadVersion", payloadVersion);
        root.put("EventTime", BASE_TIME.plusMinutes(seq).toString());
        root.put(SYNTHETIC_MARKER_FIELD, SYNTHETIC_MARKER_VALUE);
        root.set("Payload", payload);
        ObjectNode metadata = objectMapper.createObjectNode();
        metadata.put("ApplicationId", APPLICATION_ID);
        metadata.put("SubscriptionId", SUBSCRIPTION_ID);
        metadata.put("NotificationId", notificationId);
        metadata.put("PublishTime", BASE_TIME.plusMinutes(seq).plusSeconds(5).toString());
        root.set("NotificationMetadata", metadata);
        root.put("DestinationId", DESTINATION_ID);
        return root;
    }

    private ObjectNode orderChange(int seq, String orderStatus) {
        ObjectNode notification = objectMapper.createObjectNode();
        notification.put("SellerId", "A1SYNTHETICSELLER");
        notification.put("AmazonOrderId", String.format("903-%07d-%07d", 1000000 + seq, 2000000 + seq));
        notification.put("MarketplaceId", "ATVPDKIKX0DER");
        notification.put("OrderStatus", orderStatus);
        notification.put("FulfillmentChannel", "AFN");
        notification.put("ShipServiceLevel", "Standard");
        notification.put("PurchaseDate", BASE_TIME.minusHours(seq).toString());
        notification.put("LastUpdateDate", BASE_TIME.plusMinutes(seq).toString());
        ObjectNode total = objectMapper.createObjectNode();
        total.put("CurrencyCode", "USD");
        total.put("Amount", String.format("%d.00", 20 + seq * 5));
        notification.set("OrderTotal", total);
        ObjectNode payload = objectMapper.createObjectNode();
        payload.set("OrderChangeNotification", notification);
        return envelope(seq, "ORDER_CHANGE", "2022-02-02", payload,
                String.format("SYNTHETIC-NOTIF-ORDER-%04d", seq));
    }

    private ObjectNode feedFinished(int seq) {
        ObjectNode notification = objectMapper.createObjectNode();
        notification.put("FeedId", String.format("SYNTHETIC-FEED-%04d", seq));
        notification.put("FeedType", "POST_PRODUCT_DATA");
        notification.put("ProcessingStatus", "DONE");
        ObjectNode payload = objectMapper.createObjectNode();
        payload.set("FeedProcessingFinishedNotification", notification);
        return envelope(seq, "FEED_PROCESSING_FINISHED", "2021-06-30", payload,
                String.format("SYNTHETIC-NOTIF-FEED-%04d", seq));
    }

    private ObjectNode reportFinished(int seq) {
        ObjectNode notification = objectMapper.createObjectNode();
        notification.put("ReportId", String.format("SYNTHETIC-REPORT-%04d", seq));
        notification.put("ReportType", "GET_MERCHANT_LISTINGS_ALL_DATA");
        notification.put("ProcessingStatus", "DONE");
        ObjectNode payload = objectMapper.createObjectNode();
        payload.set("ReportProcessingFinishedNotification", notification);
        return envelope(seq, "REPORT_PROCESSING_FINISHED", "2021-06-30", payload,
                String.format("SYNTHETIC-NOTIF-REPORT-%04d", seq));
    }

    private ObjectNode unsupported(int seq) {
        ObjectNode notification = objectMapper.createObjectNode();
        notification.put("SomeField", "synthetic-unsupported");
        ObjectNode payload = objectMapper.createObjectNode();
        payload.set("SomeUnsupportedNotification", notification);
        return envelope(seq, "SOME_UNSUPPORTED_NOTIFICATION", "2021-01-01", payload,
                String.format("SYNTHETIC-NOTIF-UNSUPPORTED-%04d", seq));
    }
}
