package com.amz.client;

import com.google.gson.JsonObject;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Real SP-API operation executor.
 *
 * <p>All transport concerns remain in {@link SpApiGateway}; this class only
 * resolves the catalog operation into the gateway's explicit-auth entry points.</p>
 */
@Component
@Profile("!mock")
public class RealSpApiOperationClient implements SpApiOperationClient {

    private final SpApiGateway gateway;

    public RealSpApiOperationClient(SpApiGateway gateway) {
        this.gateway = gateway;
    }

    @Override
    public JsonObject execute(Long shopId,
                              String marketplaceId,
                              SpApiOperationSpec operation,
                              Map<String, String> pathParameters,
                              Map<String, String> queryParameters,
                              JsonObject body) {
        SpApiOperationClient.validateRequest(operation, pathParameters, queryParameters, body);
        SpApiGateway.ResolvedShop shop = gateway.resolveShop(shopId, marketplaceId);
        String path = SpApiOperationClient.resolvePath(operation, pathParameters);
        String query = SpApiGateway.canonicalQuery(queryParameters);
        String jsonBody = body == null ? null : body.toString();
        int[] statuses = operation.successStatuses().stream()
                .mapToInt(Integer::intValue)
                .toArray();

        if (operation.grantless()) {
            if (statuses.length != 1) {
                throw new IllegalArgumentException(
                        "grantless operation must declare exactly one success status: "
                                + operation.operationId());
            }
            return gateway.callJsonWithStatusUsingGrantlessLwa(
                    operation.method(), shop, operation.operationId(),
                    path, query, jsonBody, statuses[0]);
        }

        return gateway.callJsonWithStatuses(
                operation.method(), shop, operation.operationId(),
                path, query, jsonBody, statuses);
    }
}
