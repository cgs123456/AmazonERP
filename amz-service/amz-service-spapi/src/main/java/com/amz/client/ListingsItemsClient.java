package com.amz.client;

import com.google.gson.JsonObject;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Typed facade for the Listings Items API. */
@Component
public class ListingsItemsClient {

    private static final String FAMILY = "listingsItems";

    private final SpApiOperationClient operationClient;

    public ListingsItemsClient(SpApiOperationClient operationClient) {
        this.operationClient = operationClient;
    }

    public JsonObject deleteListingsItem(Long shopId, String marketplaceId,
                                         String sellerId, String sku,
                                         List<String> marketplaceIds,
                                         Map<String, String> optionalQuery) {
        return execute(shopId, marketplaceId, "deleteListingsItem",
                itemPath(sellerId, sku), itemQuery(marketplaceIds, optionalQuery), null);
    }

    public JsonObject getListingsItem(Long shopId, String marketplaceId,
                                      String sellerId, String sku,
                                      List<String> marketplaceIds,
                                      Map<String, String> optionalQuery) {
        return execute(shopId, marketplaceId, "getListingsItem",
                itemPath(sellerId, sku), itemQuery(marketplaceIds, optionalQuery), null);
    }

    public JsonObject patchListingsItem(Long shopId, String marketplaceId,
                                        String sellerId, String sku,
                                        List<String> marketplaceIds, JsonObject body) {
        return execute(shopId, marketplaceId, "patchListingsItem",
                itemPath(sellerId, sku), itemQuery(marketplaceIds, Map.of()), body);
    }

    public JsonObject putListingsItem(Long shopId, String marketplaceId,
                                      String sellerId, String sku,
                                      List<String> marketplaceIds, JsonObject body) {
        return execute(shopId, marketplaceId, "putListingsItem",
                itemPath(sellerId, sku), itemQuery(marketplaceIds, Map.of()), body);
    }

    public JsonObject searchListingsItems(Long shopId, String marketplaceId,
                                          String sellerId, List<String> marketplaceIds,
                                          Map<String, String> optionalQuery) {
        Map<String, String> query = mergeQuery(
                Map.of("marketplaceIds", joinRequired(marketplaceIds, "marketplaceIds")),
                optionalQuery);
        return execute(shopId, marketplaceId, "searchListingsItems",
                Map.of("sellerId", requireText(sellerId, "sellerId")), query, null);
    }

    private JsonObject execute(Long shopId, String marketplaceId, String operationName,
                               Map<String, String> path, Map<String, String> query, JsonObject body) {
        SpApiOperationSpec operation = SpApiOperationCatalog
                .find(FAMILY + "." + operationName)
                .orElseThrow(() -> new IllegalStateException("missing catalog operation: " + operationName));
        return operationClient.execute(shopId, marketplaceId, operation, path, query, body);
    }

    private static Map<String, String> itemPath(String sellerId, String sku) {
        return Map.of("sellerId", requireText(sellerId, "sellerId"),
                "sku", requireText(sku, "sku"));
    }

    private static Map<String, String> itemQuery(List<String> marketplaceIds,
                                                 Map<String, String> optionalQuery) {
        return mergeQuery(Map.of("marketplaceIds", joinRequired(marketplaceIds, "marketplaceIds")),
                optionalQuery);
    }

    private static Map<String, String> mergeQuery(Map<String, String> required,
                                                  Map<String, String> optional) {
        Map<String, String> merged = new LinkedHashMap<>(required);
        if (optional != null) {
            for (Map.Entry<String, String> entry : optional.entrySet()) {
                if (required.containsKey(entry.getKey())) {
                    throw new IllegalArgumentException(
                            "optional query must not override required parameter: " + entry.getKey());
                }
                merged.put(entry.getKey(), entry.getValue());
            }
        }
        return Map.copyOf(merged);
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
}
