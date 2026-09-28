package com.amz.client;

import com.google.gson.JsonObject;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Typed facade for the Catalog Items API. */
@Component
public class CatalogItemsClient {

    private static final String FAMILY = "catalogItems";

    private final SpApiOperationClient operationClient;

    public CatalogItemsClient(SpApiOperationClient operationClient) {
        this.operationClient = operationClient;
    }

    public JsonObject searchCatalogItems(Long shopId, String marketplaceId,
                                         List<String> marketplaceIds,
                                         Map<String, String> optionalQuery) {
        return execute(shopId, marketplaceId, "searchCatalogItems", Map.of(),
                mergeQuery(Map.of("marketplaceIds", joinRequired(marketplaceIds, "marketplaceIds")),
                        optionalQuery));
    }

    public JsonObject getCatalogItem(Long shopId, String marketplaceId, String asin,
                                     List<String> marketplaceIds,
                                     Map<String, String> optionalQuery) {
        return execute(shopId, marketplaceId, "getCatalogItem",
                Map.of("asin", requireText(asin, "asin")),
                mergeQuery(Map.of("marketplaceIds", joinRequired(marketplaceIds, "marketplaceIds")),
                        optionalQuery));
    }

    private JsonObject execute(Long shopId, String marketplaceId, String operationName,
                               Map<String, String> path, Map<String, String> query) {
        SpApiOperationSpec operation = SpApiOperationCatalog
                .find(FAMILY + "." + operationName)
                .orElseThrow(() -> new IllegalStateException("missing catalog operation: " + operationName));
        return operationClient.execute(shopId, marketplaceId, operation, path, query, null);
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
