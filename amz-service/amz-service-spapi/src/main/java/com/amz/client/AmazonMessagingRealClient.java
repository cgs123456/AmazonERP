package com.amz.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Amazon Messaging API 真实客户端。
 *
 * <p>只实现官方 messaging.json 公开的订单动作查询、订单属性查询和九类发送动作。
 * 所有请求统一经 {@link SpApiGateway} 出站，复用 LWA、AWS SigV4、区域端点、
 * 主机白名单和限流；缺凭据、缺 marketplaceId、缺请求体、非法附件结构、非预期
 * 状态码或传输失败均抛错。
 */
@Component
@Profile("!mock")
public class AmazonMessagingRealClient {

    static final String ORDERS_PATH = "/messaging/v1/orders";

    private final SpApiGateway gateway;

    public AmazonMessagingRealClient(SpApiGateway gateway) {
        this.gateway = gateway;
    }

    public JsonObject getMessagingActionsForOrder(Long shopId, String amazonOrderId,
                                                   String marketplaceId) {
        requirePositiveShopId(shopId);
        requireNonBlank(amazonOrderId, "amazonOrderId");
        requireNonBlank(marketplaceId, "marketplaceId");

        SpApiGateway.ResolvedShop shop = gateway.resolveShop(shopId, marketplaceId);
        String path = ORDERS_PATH + "/" + encodePathSegment(amazonOrderId);
        String query = SpApiGateway.canonicalQuery(Map.of("marketplaceIds", marketplaceId));
        return gateway.callJsonWithStatus("GET", shop,
                "messaging.getMessagingActionsForOrder", path, query, null, 200);
    }

    public JsonObject getOrderAttributes(Long shopId, String amazonOrderId, String marketplaceId) {
        requirePositiveShopId(shopId);
        requireNonBlank(amazonOrderId, "amazonOrderId");
        requireNonBlank(marketplaceId, "marketplaceId");

        SpApiGateway.ResolvedShop shop = gateway.resolveShop(shopId, marketplaceId);
        String path = ORDERS_PATH + "/" + encodePathSegment(amazonOrderId) + "/attributes";
        String query = SpApiGateway.canonicalQuery(Map.of("marketplaceIds", marketplaceId));
        return gateway.callJsonWithStatus("GET", shop,
                "messaging.GetAttributes", path, query, null, 200);
    }

    public JsonObject sendMessage(Long shopId, String amazonOrderId, String marketplaceId,
                                  MessagingAction action, JsonObject body) {
        requirePositiveShopId(shopId);
        requireNonBlank(amazonOrderId, "amazonOrderId");
        requireNonBlank(marketplaceId, "marketplaceId");
        if (action == null) {
            throw new IllegalArgumentException("action must not be null");
        }
        if (body == null) {
            throw new IllegalArgumentException("body must not be null for action=" + action.name());
        }
        validateAttachments(body);

        SpApiGateway.ResolvedShop shop = gateway.resolveShop(shopId, marketplaceId);
        String path = ORDERS_PATH + "/" + encodePathSegment(amazonOrderId)
                + "/messages/" + action.pathSegment();
        String query = SpApiGateway.canonicalQuery(Map.of("marketplaceIds", marketplaceId));
        return gateway.callJsonWithStatus("POST", shop, action.operationId(), path, query,
                body.toString(), 201);
    }

    /**
     * Attachment 在官方模型中声明 fileName 与 uploadDestinationId 为必填。
     * 仅当调用方提供 attachments 时校验，不虚构顶层字段的 required 约束。
     */
    private static void validateAttachments(JsonObject body) {
        JsonElement attachments = body.get("attachments");
        if (attachments == null || attachments.isJsonNull()) {
            return;
        }
        if (!attachments.isJsonArray()) {
            throw new IllegalArgumentException("attachments must be an array");
        }
        JsonArray items = attachments.getAsJsonArray();
        for (int i = 0; i < items.size(); i++) {
            JsonElement item = items.get(i);
            if (item == null || !item.isJsonObject()) {
                throw new IllegalArgumentException("attachments[" + i + "] must be an object");
            }
            JsonObject attachment = item.getAsJsonObject();
            requireNonBlankJsonString(attachment, "fileName", "attachments[" + i + "].fileName");
            requireNonBlankJsonString(attachment, "uploadDestinationId",
                    "attachments[" + i + "].uploadDestinationId");
        }
    }

    private static void requireNonBlankJsonString(JsonObject object, String field, String label) {
        JsonElement value = object.get(field);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()
                || value.getAsString().isBlank()) {
            throw new IllegalArgumentException(label + " must be a non-blank string");
        }
    }

    private static void requirePositiveShopId(Long shopId) {
        if (shopId == null || shopId <= 0) {
            throw new IllegalArgumentException("shopId must be positive");
        }
    }

    private static void requireNonBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }

    private static String encodePathSegment(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
