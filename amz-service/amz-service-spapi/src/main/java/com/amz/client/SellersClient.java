package com.amz.client;

import com.amz.connector.SpApiCallException;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Amazon SP-API Sellers v1 只读客户端。
 *
 * <p>当前只暴露无 PII 的 {@code getMarketplaceParticipations}，用于确认授权店铺
 * 可参与的 marketplace、默认币种/语言及 listing 暂停状态。账号详情
 * {@code /sellers/v1/account} 暂不接入，避免扩大账号与 PII 权限面。
 *
 * <p>鉴权、LWA 刷新、SigV4、官方限流、429 重试和 Outbox 全部由
 * {@link SpApiGateway} 统一处理；本类只声明官方 operationId、路径、成功状态码，
 * 并在返回业务层前校验官方响应契约，绝不把 payload 缺失、类型错误或必填字段异常伪装成成功。
 */
@Component
@Profile("!mock")
public class SellersClient {

    public static final String OPERATION_GET_MARKETPLACE_PARTICIPATIONS =
            "sellers.getMarketplaceParticipations";
    public static final String PATH_MARKETPLACE_PARTICIPATIONS =
            "/sellers/v1/marketplaceParticipations";

    private static final String[] MARKETPLACE_REQUIRED_FIELDS = {
            "id", "name", "countryCode", "defaultCurrencyCode", "defaultLanguageCode", "domainName"
    };

    private final SpApiGateway gateway;

    public SellersClient(SpApiGateway gateway) {
        this.gateway = gateway;
    }

    /**
     * 调用官方 {@code GET /sellers/v1/marketplaceParticipations}。
     *
     * @param shopId        店铺 ID；凭证由 {@link SpApiGateway#resolveShop(Long, String)} 解析
     * @param marketplaceId 可选 marketplace 覆盖；为空时使用凭证登记的 marketplace
     * @return 已通过必填字段与类型校验的官方响应
     * @throws SpApiCallException 平台非 200、传输失败或响应结构不符合官方 schema
     */
    public JsonObject getMarketplaceParticipations(Long shopId, String marketplaceId) {
        SpApiGateway.ResolvedShop shop = gateway.resolveShop(shopId, marketplaceId);
        JsonObject response = gateway.callJsonWithStatus(
                "GET", shop, OPERATION_GET_MARKETPLACE_PARTICIPATIONS,
                PATH_MARKETPLACE_PARTICIPATIONS, null, null, 200);
        validateResponse(response);
        return response;
    }

    private static void validateResponse(JsonObject response) {
        if (response == null) {
            invalid("response must be an object");
        }
        JsonElement payloadElement = response.get("payload");
        if (payloadElement == null || !payloadElement.isJsonArray()) {
            invalid("payload must be an array");
        }
        JsonArray payload = payloadElement.getAsJsonArray();

        for (int index = 0; index < payload.size(); index++) {
            JsonElement itemElement = payload.get(index);
            String itemPath = "payload[" + index + "]";
            if (itemElement == null || !itemElement.isJsonObject()) {
                invalid(itemPath + " must be an object");
            }
            JsonObject item = itemElement.getAsJsonObject();
            requireString(item, "storeName", itemPath);

            JsonObject marketplace = requireObject(item, "marketplace", itemPath);
            for (String field : MARKETPLACE_REQUIRED_FIELDS) {
                requireString(marketplace, field, itemPath + ".marketplace");
            }

            JsonObject participation = requireObject(item, "participation", itemPath);
            requireBoolean(participation, "isParticipating", itemPath + ".participation");
            requireBoolean(participation, "hasSuspendedListings", itemPath + ".participation");
        }
    }

    private static JsonObject requireObject(JsonObject parent, String field, String parentPath) {
        JsonElement value = parent.get(field);
        if (value == null || !value.isJsonObject()) {
            invalid(parentPath + "." + field + " must be an object");
        }
        return value.getAsJsonObject();
    }

    private static void requireString(JsonObject parent, String field, String parentPath) {
        JsonElement value = parent.get(field);
        if (value == null || !value.isJsonPrimitive()
                || !value.getAsJsonPrimitive().isString()
                || value.getAsString().isBlank()) {
            invalid(parentPath + "." + field + " must be a non-blank string");
        }
    }

    private static void requireBoolean(JsonObject parent, String field, String parentPath) {
        JsonElement value = parent.get(field);
        if (value == null || !value.isJsonPrimitive()
                || !value.getAsJsonPrimitive().isBoolean()) {
            invalid(parentPath + "." + field + " must be a boolean");
        }
    }

    private static void invalid(String diagnostic) {
        throw SpApiCallException.localFailure(
                OPERATION_GET_MARKETPLACE_PARTICIPATIONS,
                PATH_MARKETPLACE_PARTICIPATIONS,
                "INVALID_RESPONSE",
                diagnostic);
    }
}
