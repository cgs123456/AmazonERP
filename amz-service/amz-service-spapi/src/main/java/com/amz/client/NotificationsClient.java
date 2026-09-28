package com.amz.client;

import com.google.gson.JsonObject;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Typed facade for the Notifications API management operations.
 *
 * <p>The SQS/SNS delivery, signature verification, deduplication, ordering,
 * and dead-letter handling are deliberately outside this client. Those are
 * consumer concerns and must not be inferred from successful management calls.</p>
 */
@Component
public class NotificationsClient {

    private static final String FAMILY = "notifications";

    private final SpApiOperationClient operationClient;

    public NotificationsClient(SpApiOperationClient operationClient) {
        this.operationClient = operationClient;
    }

    public JsonObject getSubscriptions(Long shopId, String marketplaceId,
                                       List<String> notificationTypes,
                                       String payloadVersion, Integer pageSize, String nextToken) {
        Map<String, String> query = new LinkedHashMap<>();
        query.put("notificationTypes", joinRequired(notificationTypes, "notificationTypes"));
        putIfPresent(query, "payloadVersion", payloadVersion);
        if (pageSize != null) {
            if (pageSize <= 0) {
                throw new IllegalArgumentException("pageSize must be positive");
            }
            query.put("pageSize", pageSize.toString());
        }
        putIfPresent(query, "nextToken", nextToken);
        return execute(shopId, marketplaceId, "getSubscriptions", Map.of(), query, null);
    }

    public JsonObject getSubscription(Long shopId, String marketplaceId,
                                      String notificationType, String payloadVersion) {
        Map<String, String> query = new LinkedHashMap<>();
        putIfPresent(query, "payloadVersion", payloadVersion);
        return execute(shopId, marketplaceId, "getSubscription",
                Map.of("notificationType", requireText(notificationType, "notificationType")),
                query, null);
    }

    public JsonObject createSubscription(Long shopId, String marketplaceId,
                                         String notificationType, JsonObject body) {
        return execute(shopId, marketplaceId, "createSubscription",
                Map.of("notificationType", requireText(notificationType, "notificationType")),
                Map.of(), body);
    }

    public JsonObject getSubscriptionById(Long shopId, String marketplaceId,
                                          String notificationType, String subscriptionId) {
        return execute(shopId, marketplaceId, "getSubscriptionById",
                Map.of("notificationType", requireText(notificationType, "notificationType"),
                        "subscriptionId", requireText(subscriptionId, "subscriptionId")),
                Map.of(), null);
    }

    public JsonObject deleteSubscriptionById(Long shopId, String marketplaceId,
                                             String notificationType, String subscriptionId) {
        return execute(shopId, marketplaceId, "deleteSubscriptionById",
                Map.of("notificationType", requireText(notificationType, "notificationType"),
                        "subscriptionId", requireText(subscriptionId, "subscriptionId")),
                Map.of(), null);
    }

    public JsonObject sendTestNotification(Long shopId, String marketplaceId,
                                           String notificationType, JsonObject body) {
        return execute(shopId, marketplaceId, "sendTestNotification",
                Map.of("notificationType", requireText(notificationType, "notificationType")),
                Map.of(), body);
    }

    public JsonObject getDestinations(Long shopId, String marketplaceId) {
        return execute(shopId, marketplaceId, "getDestinations", Map.of(), Map.of(), null);
    }

    public JsonObject createDestination(Long shopId, String marketplaceId, JsonObject body) {
        return execute(shopId, marketplaceId, "createDestination", Map.of(), Map.of(), body);
    }

    public JsonObject getDestination(Long shopId, String marketplaceId, String destinationId) {
        return execute(shopId, marketplaceId, "getDestination",
                Map.of("destinationId", requireText(destinationId, "destinationId")),
                Map.of(), null);
    }

    public JsonObject deleteDestination(Long shopId, String marketplaceId, String destinationId) {
        return execute(shopId, marketplaceId, "deleteDestination",
                Map.of("destinationId", requireText(destinationId, "destinationId")),
                Map.of(), null);
    }

    private JsonObject execute(Long shopId, String marketplaceId, String operationName,
                               Map<String, String> path, Map<String, String> query, JsonObject body) {
        SpApiOperationSpec operation = SpApiOperationCatalog
                .find(FAMILY + "." + operationName)
                .orElseThrow(() -> new IllegalStateException("missing catalog operation: " + operationName));
        return operationClient.execute(shopId, marketplaceId, operation, path, query, body);
    }

    private static String joinRequired(List<String> values, String name) {
        if (values == null || values.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be empty");
        }
        for (String value : values) {
            requireText(value, name + " item");
        }
        return String.join(",", values);
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    private static void putIfPresent(Map<String, String> target, String name, String value) {
        if (value != null && !value.isBlank()) {
            target.put(name, value);
        }
    }
}
