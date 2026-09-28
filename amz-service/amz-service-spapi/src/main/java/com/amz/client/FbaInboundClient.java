package com.amz.client;

import com.google.gson.JsonObject;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/** Typed facade for the 2024-03-20 FBA Inbound API. */
@Component
public class FbaInboundClient {

    public static final String FAMILY = "fbaInbound";

    private final SpApiOperationClient operationClient;

    public FbaInboundClient(SpApiOperationClient operationClient) {
        this.operationClient = operationClient;
    }

    /** Returns the official FBA Inbound operation catalog used by this facade. */
    public List<SpApiOperationSpec> operations() {
        return SpApiOperationCatalog.operationsFor(FAMILY);
    }

    /**
     * Executes one FBA Inbound operation through the shared gateway.
     * Path/query/body validation remains centralized in {@link SpApiOperationClient}.
     */
    public JsonObject execute(Long shopId, String marketplaceId, String operationId,
                              Map<String, String> pathParameters,
                              Map<String, String> queryParameters,
                              JsonObject body) {
        SpApiOperationSpec operation = SpApiOperationCatalog.find(operationId)
                .orElseThrow(() -> new IllegalArgumentException("unknown SP-API operation: " + operationId));
        if (!FAMILY.equals(operation.family())) {
            throw new IllegalArgumentException(
                    "operation is not an FBA Inbound operation: " + operationId);
        }
        return operationClient.execute(shopId, marketplaceId, operation,
                pathParameters, queryParameters, body);
    }
}
