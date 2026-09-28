package com.amz.client;

import com.google.gson.JsonObject;
import org.springframework.stereotype.Component;

import java.util.Map;

/** Typed facade for the Product Pricing API batch operations. */
@Component
public class ProductPricingClient {

    private static final String FAMILY = "productPricing";

    private final SpApiOperationClient operationClient;

    public ProductPricingClient(SpApiOperationClient operationClient) {
        this.operationClient = operationClient;
    }

    public JsonObject getFeaturedOfferExpectedPriceBatch(Long shopId, String marketplaceId,
                                                          JsonObject body) {
        return execute(shopId, marketplaceId, "getFeaturedOfferExpectedPriceBatch", body);
    }

    public JsonObject getCompetitiveSummary(Long shopId, String marketplaceId, JsonObject body) {
        return execute(shopId, marketplaceId, "getCompetitiveSummary", body);
    }

    private JsonObject execute(Long shopId, String marketplaceId, String operationName,
                               JsonObject body) {
        SpApiOperationSpec operation = SpApiOperationCatalog
                .find(FAMILY + "." + operationName)
                .orElseThrow(() -> new IllegalStateException("missing catalog operation: " + operationName));
        return operationClient.execute(shopId, marketplaceId, operation, Map.of(), Map.of(), body);
    }
}
